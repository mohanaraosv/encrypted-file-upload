package com.github.davidcarboni.encryptedfileupload;

import com.github.davidcarboni.cryptolite.Crypto;
import com.github.davidcarboni.cryptolite.Keys;
import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileItemHeaders;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.ParameterParser;
import org.apache.commons.fileupload.util.Streams;
import org.apache.commons.io.IOUtils;

import javax.crypto.SecretKey;
import java.io.*;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.String.format;

/**
 * This class encrypts uploaded data if they are written to disk.
 *
 * Based on {@code org.apache.commons.fileupload.disk.DiskFileItem} 1643123 2014-12-03 14:10:11Z tn $ - {@code commons-fileupload 1.4-SNAPSHOT}.
 */
public class EncryptedFileItem implements FileItem {

    // ----------------------------------------------------- Manifest constants

    /**
     * The UID to use when serializing this instance.
     */
    private static final long serialVersionUID = 2237570099615271025L;

    /**
     * Default content charset to be used when no explicit charset
     * parameter is provided by the sender. Media subtypes of the
     * "text" type are defined to have a default charset value of
     * "ISO-8859-1" when received via HTTP.
     */
    public static final String DEFAULT_CHARSET = "ISO-8859-1";

    // ----------------------------------------------------------- Data members

    /**
     * UID used in unique file name generation.
     */
    private static final String UID =
            UUID.randomUUID().toString().replace('-', '_');

    /**
     * Counter used in unique identifier generation.
     */
    private static final AtomicInteger COUNTER = new AtomicInteger(0);

    /**
     * The name of the form field as provided by the browser.
     */
    private String fieldName;

    /**
     * The content type passed by the browser, or <code>null</code> if
     * not defined.
     */
    private final String contentType;

    /**
     * Whether or not this item is a simple form field.
     */
    private boolean isFormField;

    /**
     * The original filename in the user's filesystem.
     */
    private final String fileName;

    /**
     * The size of the item, in bytes. This is used to cache the size when a
     * file item is moved from its original location.
     */
    private long size = -1;


    /**
     * The threshold above which uploads will be stored on disk.
     */
    private final int sizeThreshold;

    /**
     * The directory in which uploaded files will be stored, if stored on disk.
     */
    private final File repository;

    /**
     * Cached contents of the file.
     */
    private byte[] cachedContent;

    /**
     * Output stream for this item.
     */
    private transient EncryptedDeferredOutputStream dfos;

    /**
     * The temporary file to use.
     */
    private transient File tempFile;

    /**
     * File to allow for serialization of the content of this item.
     */
    private File dfosFile;

    /**
     * The file items headers.
     */
    private FileItemHeaders headers;

    /**
     * Encryption key
     */
    private final SecretKey key;

    // ----------------------------------------------------------- Constructors

    /**
     * Constructs a new <code>EncryptedFileItem</code> instance.
     *
     * @param fieldName     The name of the form field.
     * @param contentType   The content type passed by the browser or
     *                      <code>null</code> if not specified.
     * @param isFormField   Whether or not this item is a plain form field, as
     *                      opposed to a file upload.
     * @param fileName      The original filename in the user's filesystem, or
     *                      <code>null</code> if not specified.
     * @param sizeThreshold The threshold, in bytes, below which items will be
     *                      retained in memory and above which they will be
     *                      stored as a file.
     * @param repository    The data repository, which is the directory in
     *                      which files will be created, should the item size
     *                      exceed the threshold.
     */
    public EncryptedFileItem(String fieldName,
                             String contentType, boolean isFormField, String fileName,
                             int sizeThreshold, File repository) {
        this.fieldName = fieldName;
        this.contentType = contentType;
        this.isFormField = isFormField;
        this.fileName = fileName;
        this.sizeThreshold = sizeThreshold;
        this.repository = repository;
        this.key = Keys.newSecretKey();
    }

    // ------------------------------- Methods from javax.activation.DataSource

    /**
     * Returns an {@link InputStream InputStream} that can be
     * used to retrieve the contents of the file.
     *
     * @return An {@link InputStream InputStream} that can be
     * used to retrieve the contents of the file.
     *
     * @throws IOException if an error occurs.
     */
    public InputStream getInputStream()
            throws IOException {
        if (!isInMemory()) {
            return new Crypto().decrypt(new FileInputStream(dfos.getFile()), key);
        }

        if (cachedContent == null) {
            cachedContent = dfos.getData();
        }
        return new ByteArrayInputStream(cachedContent);
    }

    /**
     * Returns the content type passed by the agent or <code>null</code> if
     * not defined.
     *
     * @return The content type passed by the agent or <code>null</code> if
     * not defined.
     */
    public String getContentType() {
        return contentType;
    }

    /**
     * Returns the content charset passed by the agent or <code>null</code> if
     * not defined.
     *
     * @return The content charset passed by the agent or <code>null</code> if
     * not defined.
     */
    public String getCharSet() {
        ParameterParser parser = new ParameterParser();
        parser.setLowerCaseNames(true);
        // Parameter parser can handle null input
        Map<String, String> params = parser.parse(getContentType(), ';');
        return params.get("charset");
    }

    /**
     * Returns the original filename in the client's filesystem.
     *
     * @return The original filename in the client's filesystem.
     * @throws org.apache.commons.fileupload.InvalidFileNameException The file name contains a NUL character,
     *                                                                which might be an indicator of a security attack. If you intend to
     *                                                                use the file name anyways, catch the exception and use
     *                                                                {@link org.apache.commons.fileupload.InvalidFileNameException#getName()}.
     */
    public String getName() {
        return Streams.checkFileName(fileName);
    }

    // ------------------------------------------------------- FileItem methods

    /**
     * Provides a hint as to whether or not the file contents will be read
     * from memory.
     *
     * @return <code>true</code> if the file contents will be read
     * from memory; <code>false</code> otherwise.
     */
    public boolean isInMemory() {
        if (cachedContent != null) {
            return true;
        }
        return dfos.isInMemory();
    }

    /**
     * Returns the size of the file.
     *
     * @return The size of the file, in bytes.
     */
    public long getSize() {
        if (size >= 0) {
            return size;
        } else if (cachedContent != null) {
            return cachedContent.length;
        } else if (dfos.isInMemory()) {
            return dfos.getData().length;
        } else {
            // Use the count of bytes written, rather than the file size
            // because encrypted file size will differ from cleartext size:
            return dfos.getByteCount();
        }
    }

    /**
     * Returns the contents of the file as an array of bytes.  If the
     * contents of the file were not yet cached in memory, they will be
     * loaded from the disk storage and cached.
     *
     * @return The contents of the file as an array of bytes
     * or {@code null} if the data cannot be read
     */
    public byte[] get() {
        if (isInMemory()) {
            if (cachedContent == null && dfos != null) {
                cachedContent = dfos.getData();
            }
            return cachedContent;
        }

        // Read(byte[]) seems to only read 512 bytes.
        // This may be something to do with CipherInputStream
        // so we use IOUtils.copy(...) instead of read(...)
        byte[] fileData = new byte[(int) getSize()];
        InputStream fis = null;

        try {
            fis = new Crypto().decrypt(new BufferedInputStream( new FileInputStream(dfos.getFile())), key);
            IOUtils.readFully(fis, fileData);
             //ByteArrayOutputStream output = new ByteArrayOutputStream((int) getSize())) {
            //IOUtils.copy(input, output);
            //fileData = output.toByteArray();
        } catch (IOException e) {
            fileData = null;
        } finally {
            IOUtils.closeQuietly(fis);
        }

        return fileData;
    }

    /**
     * Returns the contents of the file as a String, using the specified
     * encoding.  This method uses {@link #get()} to retrieve the
     * contents of the file.
     *
     * @param charset The charset to use.
     *
     * @return The contents of the file, as a string.
     *
     * @throws UnsupportedEncodingException if the requested character
     *                                      encoding is not available.
     */
    public String getString(final String charset)
            throws UnsupportedEncodingException {
        return new String(get(), charset);
    }

    /**
     * Returns the contents of the file as a String, using the default
     * character encoding.  This method uses {@link #get()} to retrieve the
     * contents of the file.
     *
     * <b>TODO</b> Consider making this method throw UnsupportedEncodingException.
     *
     * @return The contents of the file, as a string.
     */
    public String getString() {
        byte[] rawdata = get();
        String charset = getCharSet();
        if (charset == null) {
            charset = DEFAULT_CHARSET;
        }
        try {
            return new String(rawdata, charset);
        } catch (UnsupportedEncodingException e) {
            return new String(rawdata);
        }
    }

    /**
     * A convenience method to write an uploaded item to disk. The client code
     * is not concerned with whether or not the item is stored in memory, or on
     * disk in a temporary location. They just want to write the uploaded item
     * to a file.
     *
     * This implementation first attempts to rename the uploaded item to the
     * specified destination file, if the item was originally written to disk.
     * Otherwise, the data will be copied to the specified file.
     *
     * This method is only guaranteed to work <em>once</em>, the first time it
     * is invoked for a particular item. This is because, in the event that the
     * method renames a temporary file, that file will no longer be available
     * to copy or rename again at a later time.
     *
     * @param file The <code>File</code> into which the uploaded item should
     *             be stored.
     *
     * @throws Exception if an error occurs.
     */
    public void write(File file) throws Exception {
        if (isInMemory()) {
            FileOutputStream fout = null;
            try {
                fout = new FileOutputStream(file);
                fout.write(get());
                fout.close();
            } finally {
            	IOUtils.closeQuietly(fout);
            }
        } else {
            File outputFile = getStoreLocation();
            if (outputFile != null) {
                // Save the length of the file
                size = outputFile.length();
                /*
                 * The uploaded file is encrypted on disk
                 * in a temporary location so must be decrypted
                 * into the desired file.
                 */
                InputStream in = null;
                OutputStream out = null;
                try {
                    in = new Crypto().decrypt(new FileInputStream(outputFile), key);
                    out = new FileOutputStream(file);
                    IOUtils.copy(in, out);
                } finally {
                    IOUtils.closeQuietly(in);
                    IOUtils.closeQuietly(out);
                }
            } else {
                /*
                 * For whatever reason we cannot write the
                 * file to disk.
                 */
                throw new FileUploadException(
                        "Cannot write uploaded file to disk!");
            }
        }
    }

    /**
     * Deletes the underlying storage for a file item, including deleting any
     * associated temporary disk file. Although this storage will be deleted
     * automatically when the <code>FileItem</code> instance is garbage
     * collected, this method can be used to ensure that this is done at an
     * earlier time, thus preserving system resources.
     */
    public void delete() {
        cachedContent = null;
        File outputFile = getStoreLocation();
        if (outputFile != null && outputFile.exists()) {
            outputFile.delete();
        }
    }

    /**
     * Returns the name of the field in the multipart form corresponding to
     * this file item.
     *
     * @return The name of the form field.
     *
     * @see #setFieldName(String)
     *
     */
    public String getFieldName() {
        return fieldName;
    }

    /**
     * Sets the field name used to reference this file item.
     *
     * @param fieldName The name of the form field.
     *
     * @see #getFieldName()
     *
     */
    public void setFieldName(String fieldName) {
        this.fieldName = fieldName;
    }

    /**
     * Determines whether or not a <code>FileItem</code> instance represents
     * a simple form field.
     *
     * @return <code>true</code> if the instance represents a simple form
     * field; <code>false</code> if it represents an uploaded file.
     *
     * @see #setFormField(boolean)
     *
     */
    public boolean isFormField() {
        return isFormField;
    }

    /**
     * Specifies whether or not a <code>FileItem</code> instance represents
     * a simple form field.
     *
     * @param state <code>true</code> if the instance represents a simple form
     *              field; <code>false</code> if it represents an uploaded file.
     *
     * @see #isFormField()
     *
     */
    public void setFormField(boolean state) {
        isFormField = state;
    }

    /**
     * Returns an {@link OutputStream OutputStream} that can
     * be used for storing the contents of the file.
     *
     * @return An {@link OutputStream OutputStream} that can be used
     *         for storing the contents of the file.
     *
     * @throws IOException if an error occurs.
     */
    public OutputStream getOutputStream()
            throws IOException {
        if (dfos == null) {
            File outputFile = getTempFile();
            dfos = new EncryptedDeferredOutputStream(sizeThreshold, outputFile, key);
        }
        return dfos;
    }

    // --------------------------------------------------------- Public methods

    /**
     * Returns the {@link File} object for the <code>FileItem</code>'s
     * data's temporary location on the disk. Note that for
     * <code>FileItem</code>s that have their data stored in memory,
     * this method will return <code>null</code>. When handling large
     * files, you can use {@link File#renameTo(File)} to
     * move the file to new location without copying the data, if the
     * source and destination locations reside within the same logical
     * volume.
     *
     * @return The data file, or <code>null</code> if the data is stored in
     * memory.
     */
    public File getStoreLocation() {
        if (dfos == null) {
            return null;
        }
        if (isInMemory()) {
        	return null;
        }
        return dfos.getFile();
    }

    // ------------------------------------------------------ Protected methods

    /**
     * Removes the file contents from the temporary storage.
     */
    @Override
    protected void finalize() {
        if (dfos == null) {
            return;
        }
        File outputFile = dfos.getFile();

        if (outputFile != null && outputFile.exists()) {
            outputFile.delete();
        }
    }

    /**
     * Creates and returns a {@link File File} representing a uniquely
     * named temporary file in the configured repository path. The lifetime of
     * the file is tied to the lifetime of the <code>FileItem</code> instance;
     * the file will be deleted when the instance is garbage collected.
     *
     * <b>Note: Subclasses that override this method must ensure that they return the
     * same File each time.</b>
     *
     * @return The {@link File File} to be used for temporary storage.
     */
    protected File getTempFile() {
        if (tempFile == null) {
            File tempDir = repository;
            if (tempDir == null) {
                tempDir = new File(System.getProperty("java.io.tmpdir"));
            }

            String tempFileName = format("upload_%s_%s.tmp", UID, getUniqueId());

            tempFile = new File(tempDir, tempFileName);
        }
        return tempFile;
    }

    // -------------------------------------------------------- Private methods

    /**
     * Returns an identifier that is unique within the class loader used to
     * load this class, but does not have random-like appearance.
     *
     * @return A String with the non-random looking instance identifier.
     */
    private static String getUniqueId() {
        final int limit = 100000000;
        int current = COUNTER.getAndIncrement();
        String id = Integer.toString(current);

        // If you manage to get more than 100 million of ids, you'll
        // start getting ids longer than 8 characters.
        if (current < limit) {
            id = ("00000000" + id).substring(id.length());
        }
        return id;
    }

    /**
     * Returns a string representation of this object.
     *
     * @return a string representation of this object.
     */
    @Override
    public String toString() {
        return format("name=%s, StoreLocation=%s, size=%s bytes, isFormField=%s, FieldName=%s",
                getName(), getStoreLocation(), Long.valueOf(getSize()),
                Boolean.valueOf(isFormField()), getFieldName());
    }

    // -------------------------------------------------- Serialization methods

    /**
     * Writes the state of this object during serialization.
     *
     * @param out The stream to which the state should be written.
     *
     * @throws IOException if an error occurs.
     */
    private void writeObject(ObjectOutputStream out) throws IOException {
        // Read the data
        if (dfos.isInMemory()) {
            cachedContent = get();
        } else {
            cachedContent = null;
            dfosFile = dfos.getFile();
        }

        // write out values
        out.defaultWriteObject();
    }

    /**
     * Reads the state of this object during deserialization.
     *
     * @param in The stream from which the state should be read.
     *
     * @throws IOException            if an error occurs.
     * @throws ClassNotFoundException if class cannot be found.
     */
    private void readObject(ObjectInputStream in)
            throws IOException, ClassNotFoundException {
        // read values
        in.defaultReadObject();

        /* One expected use of serialization is to migrate HTTP sessions
         * containing a EncryptedFileItem between JVMs. Particularly if the JVMs are
         * on different machines It is possible that the repository location is
         * not valid so validate it.
         */
        if (repository != null) {
            if (repository.isDirectory()) {
                // Check path for nulls
                if (repository.getPath().contains("\0")) {
                    throw new IOException(format(
                            "The repository [%s] contains a null character",
                            repository.getPath()));
                }
            } else {
                throw new IOException(format(
                        "The repository [%s] is not a directory",
                        repository.getAbsolutePath()));
            }
        }

        OutputStream output = getOutputStream();
        if (cachedContent != null) {
            output.write(cachedContent);
        } else {
            // Decrypt and re-encrypt the data into the new file
            InputStream input = new Crypto().decrypt( new FileInputStream(dfosFile), key);
            IOUtils.copy(input, output);
            IOUtils.closeQuietly(input);
            dfosFile.delete();
            dfosFile = null;
        }
        output.close();

        cachedContent = null;
    }

    /**
     * Returns the file item headers.
     * @return The file items headers.
     */
    public FileItemHeaders getHeaders() {
        return headers;
    }

    /**
     * Sets the file item headers.
     * @param pHeaders The file items headers.
     */
    public void setHeaders(FileItemHeaders pHeaders) {
        headers = pHeaders;
    }

}
