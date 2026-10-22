// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.utils

import android.app.Application
import android.os.Bundle
import android.os.Environment
import android.os.Parcel
import android.util.Log
import io.reactivex.rxjava3.core.Completable
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.PrintStream

/**
 * A utility class containing helpful methods
 * pertaining to file storage.
 */
object FileUtils {

    private const val TAG = "FileUtils"

    @Suppress("DEPRECATION")
    val DEFAULT_DOWNLOAD_PATH: String =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path

    /**
     * Writes a bundle to persistent storage in the files directory
     * using the specified file name. This method is a blocking
     * operation.
     *
     * @param app    the application needed to obtain the file directory.
     * @param bundle the bundle to store in persistent storage.
     * @param name   the name of the file to store the bundle in.
     */
    fun writeBundleToStorage(app: Application, bundle: Bundle, name: String): Completable =
        Completable.fromAction {
            val outputFile = File(app.filesDir, name)
            var outputStream: FileOutputStream? = null
            try {
                outputStream = FileOutputStream(outputFile)
                val parcel = Parcel.obtain()
                parcel.writeBundle(bundle)
                outputStream.write(parcel.marshall())
                outputStream.flush()
                parcel.recycle()
            } catch (e: IOException) {
                Log.e(TAG, "Unable to write bundle to storage")
            } finally {
                Utils.close(outputStream)
            }
        }

    /**
     * Use this method to delete the bundle with the specified name.
     * This is a blocking call and should be used within a worker
     * thread unless immediate deletion is necessary.
     *
     * @param app  the application object needed to get the file.
     * @param name the name of the file.
     */
    fun deleteBundleInStorage(app: Application, name: String) {
        val outputFile = File(app.filesDir, name)
        if (outputFile.exists()) {
            outputFile.delete()
        }
    }

    /**
     * Reads a bundle from the file with the specified
     * name in the peristent storage files directory.
     * This method is a blocking operation.
     *
     * @param app  the application needed to obtain the files directory.
     * @param name the name of the file to read from.
     * @return a valid Bundle loaded using the system class loader
     * or null if the method was unable to read the Bundle from storage.
     */
    fun readBundleFromStorage(app: Application, name: String): Bundle? {
        val inputFile = File(app.filesDir, name)
        var inputStream: FileInputStream? = null
        try {
            inputStream = FileInputStream(inputFile)
            val parcel = Parcel.obtain()
            val data = inputStream.readBytes()

            parcel.unmarshall(data, 0, data.size)
            parcel.setDataPosition(0)
            // The app's loader, not the system one: saved bundles can
            // carry engine Parcelables (GeckoView SessionState) that
            // the system loader cannot resolve.
            val out = parcel.readBundle(app.classLoader)
            out?.putAll(out)
            parcel.recycle()
            return out
        } catch (e: FileNotFoundException) {
            Log.e(TAG, "Unable to read bundle from storage")
        } catch (e: IOException) {
            Log.e(TAG, "Unable to read bundle from storage", e)
        } catch (e: RuntimeException) {
            // A corrupt or ununmarshallable parcel (BadParcelableException
            // on an engine Parcelable) must degrade to "nothing saved",
            // not propagate and error the whole restore stream.
            Log.e(TAG, "Unable to unparcel saved bundle", e)
        } finally {
            Utils.close(inputStream)
        }
        return null
    }

    /**
     * Writes a stacktrace to the app-private crash directory with
     * the following filename: [EXCEPTION]_[TIME OF CRASH IN MILLIS].txt
     * Private storage — stacktraces can embed URLs carrying tokens.
     *
     * @param app       the application whose files directory holds the dumps.
     * @param throwable the Throwable to log to storage
     */
    fun writeCrashToStorage(app: Application, throwable: Throwable) {
        val fileName = throwable.javaClass.simpleName + '_' + System.currentTimeMillis() + ".txt"
        val outputFile = File(File(app.filesDir, "crash"), fileName)

        var outputStream: FileOutputStream? = null
        try {
            outputFile.parentFile?.mkdirs()
            outputStream = FileOutputStream(outputFile)
            throwable.printStackTrace(PrintStream(outputStream))
            outputStream.flush()
        } catch (e: IOException) {
            Log.e(TAG, "Unable to write bundle to storage")
        } finally {
            Utils.close(outputStream)
        }
    }

    /**
     * Converts megabytes to bytes.
     *
     * @param megaBytes the number of megabytes.
     * @return the converted bytes.
     */
    fun megabytesToBytes(megaBytes: Long): Long = megaBytes * 1024 * 1024

    /**
     * Determine whether there is write access in the given directory. Returns false if a
     * file cannot be created in the directory or if the directory does not exist.
     *
     * @param directory the directory to check for write access
     * @return returns true if the directory can be written to or is in a directory that can
     * be written to. false if there is no write access.
     */
    fun isWriteAccessAvailable(directory: String?): Boolean {
        if (directory.isNullOrEmpty()) {
            return false
        }

        val sFileName = "test"
        val sFileExtension = ".txt"
        var dir = addNecessarySlashes(directory)
        dir = getFirstRealParentDirectory(dir)
        var file = File(dir + sFileName + sFileExtension)
        for (n in 0 until 100) {
            if (!file.exists()) {
                try {
                    if (file.createNewFile() && !file.delete()) {
                        Log.w(TAG, "Unable to delete probe file: $file")
                    }
                    return true
                } catch (ignored: IOException) {
                    return false
                }
            } else {
                file = File("$dir$sFileName-$n$sFileExtension")
            }
        }
        return file.canWrite()
    }

    /**
     * Returns the first parent directory of a directory that exists. This is useful
     * for subdirectories that do not exist but their parents do.
     *
     * @param directory the directory to find the first existent parent
     * @return the first existent parent
     */
    private fun getFirstRealParentDirectory(directory: String?): String {
        var directory = directory
        while (true) {
            if (directory.isNullOrEmpty()) {
                return "/"
            }
            directory = addNecessarySlashes(directory)
            val file = File(directory)
            if (!file.isDirectory) {
                val indexSlash = directory.lastIndexOf('/')
                if (indexSlash > 0) {
                    val parent = directory.substring(0, indexSlash)
                    val previousIndex = parent.lastIndexOf('/')
                    directory = if (previousIndex > 0) {
                        parent.substring(0, previousIndex)
                    } else {
                        return "/"
                    }
                } else {
                    return "/"
                }
            } else {
                return directory
            }
        }
    }

    fun addNecessarySlashes(originalPath: String?): String {
        var originalPath = originalPath
        if (originalPath.isNullOrEmpty()) {
            return "/"
        }
        if (originalPath[originalPath.length - 1] != '/') {
            originalPath += '/'
        }
        if (originalPath[0] != '/') {
            originalPath = "/$originalPath"
        }
        return originalPath
    }
}
