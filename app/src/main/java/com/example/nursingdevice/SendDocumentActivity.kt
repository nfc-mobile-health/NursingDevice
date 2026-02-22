package com.example.nursingdevice
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class SendDocumentActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var fileNameText: TextView
    private lateinit var fileSizeText: TextView

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_send_document)

        statusText = findViewById(R.id.DisplaySelectedDocument)
        fileNameText = findViewById(R.id.syncFileNameText)
        fileSizeText = findViewById(R.id.syncStatusText)

        // Get file path from intent
        val filePath = intent.getStringExtra("FILE_PATH")
        val fileName = intent.getStringExtra("FILE_NAME")

        if (filePath != null) {
            handleMedicalDataFile(filePath, fileName)
        } else {
            Toast.makeText(this, "Error: No file to send", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun handleMedicalDataFile(filePath: String, fileName: String?) {
        try {
            val file = File(filePath)

            if (!file.exists()) {
                Toast.makeText(this, "Error: File not found", Toast.LENGTH_SHORT).show()
                finish()
                return
            }

            // 1. Read the file's raw byte content
            val fileContent = file.readBytes()

            if (fileContent.isEmpty()) {
                Toast.makeText(this, "Error: Could not read file content.", Toast.LENGTH_SHORT).show()
                return
            }

            // 2. Set MIME type for text file
            val mimeType = "text/plain"

            // 3. Prepare the HostApduService with the data for transfer
            MyHostApduService.setFileForTransfer(fileContent, mimeType)

            // Update UI to show the file is ready
            val displayName = fileName ?: file.name
            statusText.text = "Ready to send: $displayName"
            fileNameText.text = displayName
            fileSizeText.text = "File size: ${fileContent.size} bytes"

            Log.d("SendDocumentActivity", "Medical data file prepared. Size: ${fileContent.size} bytes, MIME: $mimeType")
            Toast.makeText(this, "File is ready. Tap a reader device.", Toast.LENGTH_LONG).show()

        } catch (e: Exception) {
            Log.e("SendDocumentActivity", "Failed to handle medical data file", e)
            Toast.makeText(this, "Error processing file: ${e.message}", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up the service state when the activity is closed
        MyHostApduService.resetTransferState()
        Log.d("SendDocumentActivity", "Activity destroyed, transfer state reset.")
    }
}