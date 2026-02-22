package com.example.nursingdevice
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject


class GetPatientActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {

    private lateinit var statusText: TextView
    private lateinit var dataText: TextView
    private lateinit var manager: NursePatientManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_get_patient)

        manager = NursePatientManager(this)
        statusText = findViewById(R.id.statusText)
        dataText = findViewById(R.id.receivedDataText)

        statusText.text = "👤 Tap aggregator to get patient details..."
        enableNFC()
    }

    private fun enableNFC() {
        NfcAdapter.getDefaultAdapter(this)?.enableReaderMode(
            this, this,
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null
        )
    }

    override fun onTagDiscovered(tag: Tag?) {
        val isoDep = IsoDep.get(tag) ?: return
        try {
            isoDep.connect()

            // Standard protocol (same as ReaderActivity)
            var response = isoDep.transceive(Utils.SELECT_APD)
            if (!response.isSuccess()) throw Exception("Connection failed")

            response = isoDep.transceive(Utils.GET_FILE_INFO_COMMAND)
            if (!response.isSuccess()) throw Exception("No data")

            val metadata = response.getData()
            val transferMode = String(metadata.copyOfRange(0, 1), Charsets.UTF_8)

            if (transferMode == "F") {
                // Single file - get patient JSON
                val fileInfo = metadata.copyOfRange(1, metadata.size)
                val fileSize = java.nio.ByteBuffer.wrap(fileInfo.copyOfRange(0, 4)).int
                val fileName = String(fileInfo.copyOfRange(4, fileInfo.size), Charsets.UTF_8)

                runOnUiThread {
                    statusText.text = "📥 Receiving: $fileName"
                    dataText.text = "Receiving patient data..."
                }

                // Read full file (simplified - first chunk for JSON)
                val chunkResponse = isoDep.transceive(Utils.GET_NEXT_DATA_CHUNK_COMMAND)
                if (chunkResponse.isSuccess()) {
                    val patientJson = String(chunkResponse.getData(), Charsets.UTF_8)

                    // Check if it's patient data
                    val json = JSONObject(patientJson)
                    if (json.has("type") && json.getString("type") == "PATIENT_DATA") {
                        manager.savePatient(patientJson)
                        runOnUiThread {
                            statusText.text = "✅ Patient details received!"
                            dataText.text = "Patient: ${json.getString("name")}\nAge: ${json.getInt("age")}\nSaved to cache!"
                            Toast.makeText(this, "✅ Patient saved! Go back to main.", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        runOnUiThread {
                            dataText.text = "Not patient data: $patientJson"
                            Toast.makeText(this, "Regular file received", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

        } catch (e: Exception) {
            runOnUiThread {
                statusText.text = "❌ Error: ${e.message}"
                Toast.makeText(this, e.message, Toast.LENGTH_SHORT).show()
            }
        } finally {
            isoDep.close()
        }
    }

    private fun ByteArray.isSuccess(): Boolean =
        this.size >= 2 && this.takeLast(2) == Utils.SELECT_OK_SW.toList()

    private fun ByteArray.getData(): ByteArray = this.copyOfRange(0, this.size - 2)

    override fun onPause() {
        super.onPause()
        NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this)
    }

    override fun onResume() {
        super.onResume()
        enableNFC()
    }
}
