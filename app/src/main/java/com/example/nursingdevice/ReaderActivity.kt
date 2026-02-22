package com.example.nursingdevice
import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer

class ReaderActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {

    private var statusTextView: TextView? = null
    private var receivedDataTextView: TextView? = null
    private var scrollView: ScrollView? = null
    private val APP_DIRECTORY = "NursingDevice"
    private val CHUNK_SIZE = 245
    private val MAX_CHUNK_COUNT = 50000

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)

        // ✅ Safe findViewById with null checks
        try {
            statusTextView = findViewById(R.id.statusText)
            receivedDataTextView = findViewById(R.id.receivedDataText)
            scrollView = findViewById(R.id.scrollView)

            // Verify all views are found
            if (statusTextView == null) {
                Log.e("ReaderActivity", "statusText view not found in layout")
                Toast.makeText(this, "Layout error: statusText not found", Toast.LENGTH_SHORT).show()
                return
            }
            if (receivedDataTextView == null) {
                Log.e("ReaderActivity", "receivedDataText view not found in layout")
                Toast.makeText(this, "Layout error: receivedDataText not found", Toast.LENGTH_SHORT).show()
                return
            }

            statusTextView?.text = "📱 Waiting for NFC device..."
            enableNFC()

        } catch (e: Exception) {
            Log.e("ReaderActivity", "Error initializing views: ${e.message}", e)
            Toast.makeText(this, "Initialization error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun enableNFC() {
        statusTextView?.text = "📱 Waiting for NFC device..."
        NfcAdapter.getDefaultAdapter(this)?.enableReaderMode(
            this, this,
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null
        )
    }

    override fun onTagDiscovered(tag: Tag?) {
        Log.d("ReaderActivity", "NFC Tag Discovered!")
        runOnUiThread {
            statusTextView?.text = "✅ Connected to device"
            receivedDataTextView?.text = "Receiving file..."
        }

        val isoDep = IsoDep.get(tag) ?: return
        try {
            isoDep.connect()
            isoDep.timeout = 5000

            var response = isoDep.transceive(Utils.SELECT_APD)
            if (!response.isSuccess()) throw IOException("AID selection failed.")

            response = isoDep.transceive(Utils.GET_FILE_INFO_COMMAND)
            if (!response.isSuccess()) throw IOException("Failed to get metadata.")

            val metadataPayload = response.getData()
            val transferMode = String(metadataPayload.copyOfRange(0, 1), Charsets.UTF_8)

            when (transferMode) {
                "T" -> handleTextReception(metadataPayload.copyOfRange(1, metadataPayload.size))
                "F" -> handleFileReception(isoDep, metadataPayload.copyOfRange(1, metadataPayload.size))
                "M" -> handleMultiFileReception(isoDep)
                else -> throw IOException("Unknown transfer mode: $transferMode")
            }

        } catch (e: IOException) {
            Log.e("ReaderActivity", "❌ Error: ${e.message}", e)
            runOnUiThread {
                statusTextView?.text = "❌ Connection Error"
                receivedDataTextView?.text = e.message ?: "Unknown error"
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } finally {
            try { isoDep.close() } catch (e: IOException) { }
        }
    }

    private fun handleFileReception(isoDep: IsoDep, fileInfoPayload: ByteArray) {
        val fileSize = ByteBuffer.wrap(fileInfoPayload.copyOfRange(0, 4)).int
        val fileName = String(fileInfoPayload.copyOfRange(4, fileInfoPayload.size), Charsets.UTF_8)

        Log.d("ReaderActivity", "Receiving file: $fileName, Size=$fileSize bytes")
        runOnUiThread { receivedDataTextView?.text = "📥 Receiving: $fileName\n($fileSize bytes)" }

        val tempFile = File(cacheDir, fileName)
        var receivedBytes = 0

        try {
            FileOutputStream(tempFile).use { fos ->
                var chunkCount = 0
                while (receivedBytes < fileSize && chunkCount < MAX_CHUNK_COUNT) {
                    val response = isoDep.transceive(Utils.GET_NEXT_DATA_CHUNK_COMMAND)
                    if (!response.isSuccess()) break

                    val chunkData = response.getData()
                    fos.write(chunkData)
                    receivedBytes += chunkData.size
                    chunkCount++

                    val progress = (receivedBytes * 100 / fileSize)
                    runOnUiThread {
                        statusTextView?.text = "⏳ $receivedBytes / $fileSize bytes ($progress%)"
                    }
                }
            }

            if (receivedBytes != fileSize) {
                Log.w("ReaderActivity", "⚠️ File transfer incomplete: $receivedBytes / $fileSize bytes")
            }

            displayFileInView(tempFile)
            runOnUiThread {
                statusTextView?.text = "✅ Transfer Complete!"
                Toast.makeText(this, "✅ File received!", Toast.LENGTH_SHORT).show()
            }

        } catch (e: Exception) {
            Log.e("ReaderActivity", "❌ Error: ${e.message}", e)
            tempFile.delete()
            runOnUiThread {
                statusTextView?.text = "❌ Error"
                receivedDataTextView?.text = e.message ?: "Unknown error"
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleMultiFileReception(isoDep: IsoDep) {
        Log.d("ReaderActivity", "Starting multi-file reception (streaming mode)...")

        val fileName = "aggregated_reports.txt"
        val tempFile = File(cacheDir, fileName)

        try {
            val response = isoDep.transceive(Utils.GET_FILE_INFO_COMMAND)
            if (!response.isSuccess()) throw IOException("Failed to get file metadata.")

            val metadataPayload = response.getData()
            val fileSize = ByteBuffer.wrap(metadataPayload.copyOfRange(0, 4)).int
            val receivedFileName = String(metadataPayload.copyOfRange(4, metadataPayload.size), Charsets.UTF_8)

            Log.d("ReaderActivity", "Receiving appended file: $receivedFileName, Size=$fileSize bytes")
            runOnUiThread {
                statusTextView?.text = "📥 Receiving: $receivedFileName"
                receivedDataTextView?.text = "File size: $fileSize bytes\nStreaming to device..."
            }

            var receivedBytes = 0
            var chunkCount = 0

            FileOutputStream(tempFile).use { fos ->
                while (receivedBytes < fileSize && chunkCount < MAX_CHUNK_COUNT) {
                    val chunkResponse = isoDep.transceive(Utils.GET_NEXT_DATA_CHUNK_COMMAND)
                    if (!chunkResponse.isSuccess()) break

                    val chunkData = chunkResponse.getData()
                    fos.write(chunkData)
                    receivedBytes += chunkData.size
                    chunkCount++

                    val progress = (receivedBytes * 100 / fileSize)
                    runOnUiThread {
                        statusTextView?.text = "⏳ $receivedBytes / $fileSize bytes ($progress%)"
                    }

                    if (chunkCount % 100 == 0) {
                        Log.d("ReaderActivity", "Progress: $receivedBytes / $fileSize bytes")
                    }
                }
            }

            Log.d("ReaderActivity", "✅ File streaming complete: $receivedBytes bytes")

            displayFileInView(tempFile)
            runOnUiThread {
                statusTextView?.text = "✅ Transfer Complete!"
                Toast.makeText(this, "✅ File received and displayed!", Toast.LENGTH_SHORT).show()
            }

        } catch (e: OutOfMemoryError) {
            Log.e("ReaderActivity", "❌ OutOfMemory Error: ${e.message}", e)
            tempFile.delete()
            runOnUiThread {
                statusTextView?.text = "❌ File Too Large"
                receivedDataTextView?.text = "OutOfMemoryError: File exceeds app memory limit"
                Toast.makeText(this, "File too large to handle!", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("ReaderActivity", "❌ Error: ${e.message}", e)
            tempFile.delete()
            runOnUiThread {
                statusTextView?.text = "❌ Error"
                receivedDataTextView?.text = e.message ?: "Unknown error"
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleTextReception(payload: ByteArray) {
        val receivedString = String(payload, Charsets.UTF_8)
        Log.d("ReaderActivity", "Received text: $receivedString")
        runOnUiThread {
            statusTextView?.text = "✅ Text received!"
            receivedDataTextView?.text = receivedString
            scrollView?.post {
                scrollView?.scrollTo(0, 0)
            }
        }
    }

    private fun displayFileInView(file: File) {
        try {
            Log.d("ReaderActivity", "Displaying file directly in view: ${file.absolutePath}")

            val content = file.readText(Charsets.UTF_8)

            runOnUiThread {
                receivedDataTextView?.text = content
                statusTextView?.text = "✅ File content displayed!"

                scrollView?.post {
                    scrollView?.scrollTo(0, 0)
                }
            }

        } catch (e: Exception) {
            Log.e("ReaderActivity", "❌ Error displaying file: ${e.message}", e)
            runOnUiThread {
                statusTextView?.text = "❌ Error displaying file"
                receivedDataTextView?.text = "Error: ${e.message}"
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun ByteArray.isSuccess(): Boolean =
        this.size >= 2 && this.takeLast(2) == Utils.SELECT_OK_SW.toList()

    private fun ByteArray.getData(): ByteArray =
        this.copyOfRange(0, this.size - 2)

    override fun onPause() {
        super.onPause()
        NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this)
    }

    override fun onResume() {
        super.onResume()
        NfcAdapter.getDefaultAdapter(this)?.enableReaderMode(
            this, this,
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null
        )
    }
}