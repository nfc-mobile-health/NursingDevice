package com.example.nursingdevice

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File

class SendDocumentActivity : AppCompatActivity() {

    private lateinit var headerStatusText: TextView
    private lateinit var fileNameText: TextView
    private lateinit var detailedLogText: TextView

    private val authReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent?.getStringExtra("step_message")
            if (message != null) {
                runOnUiThread {
                    // Update the header if it is a major state change
                    if (message == "Step 1: Connection Established") {
                        headerStatusText.text = "Authenticating..."
                    } else if (message == "Transmitting Data...") {
                        headerStatusText.text = "Sending Data..."
                    }

                    // Append the detailed step to the bottom log area
                    val currentText = detailedLogText.text.toString()
                    if (currentText.startsWith("File size") || currentText.startsWith("Hold device")) {
                        detailedLogText.text = message
                    } else {
                        detailedLogText.text = "$currentText\n$message"
                    }
                }
            }
        }
    }

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_send_document)

        headerStatusText = findViewById(R.id.DisplaySelectedDocument)
        fileNameText = findViewById(R.id.syncFileNameText)
        detailedLogText = findViewById(R.id.syncStatusText)

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

            val fileContent = file.readBytes()

            if (fileContent.isEmpty()) {
                Toast.makeText(this, "Error: Could not read file content.", Toast.LENGTH_SHORT).show()
                return
            }

            val mimeType = "text/plain"

            MyHostApduService.setFileForTransfer(fileContent, mimeType)

            val displayName = fileName ?: file.name

            // Set the clean initial state
            headerStatusText.text = "Waiting for Receiver..."
            fileNameText.text = "File: $displayName"
            detailedLogText.text = "File size: ${fileContent.size} bytes\nReady to transmit. Hold near reader."

        } catch (e: Exception) {
            Log.e("SendDocumentActivity", "Failed to handle medical data file", e)
            Toast.makeText(this, "Error processing file: ${e.message}", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter("NFC_AUTH_STEP")
        ContextCompat.registerReceiver(this, authReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(authReceiver)
        } catch (e: Exception) {
            // Ignored
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        MyHostApduService.resetTransferState()
    }
}