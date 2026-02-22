package com.example.nursingdevice
import android.Manifest
import android.app.DatePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class SendForm : AppCompatActivity(), RecognitionListener { // Implement RecognitionListener

    private lateinit var nurseIdInput: EditText
    private lateinit var nameInput: EditText
    private lateinit var bloodGroupSpinner: Spinner
    private lateinit var genderSpinner: Spinner
    private lateinit var dobInput: EditText
    private lateinit var bpInput: EditText
    private lateinit var heartRateInput: EditText
    private lateinit var respiratoryRateInput: EditText
    private lateinit var temperatureInput: EditText
    private lateinit var generateButton: Button
    private lateinit var startVoiceBtn: Button

    // Voice input buttons
    private lateinit var voiceNurseIdBtn: ImageButton
    private lateinit var voiceNameBtn: ImageButton
    private lateinit var voiceBpBtn: ImageButton
    private lateinit var voiceHeartRateBtn: ImageButton
    private lateinit var voiceRespRateBtn: ImageButton
    private lateinit var voiceTempBtn: ImageButton

    private lateinit var formContainer: LinearLayout
    private lateinit var previewContainer: LinearLayout
    private lateinit var fileContentText: TextView
    private lateinit var fileNameText: TextView
    private lateinit var editButton: Button
    private lateinit var sendNfcButton: Button

    private var generatedFile: File? = null
    private var fileContent: String = ""

    // Current field index for auto-progression
    private var currentFieldIndex: Int = 0
    private val voiceEnabledFields = mutableListOf<EditText>()

    private val RECORD_AUDIO_PERMISSION_CODE = 100

    // New Speech Recognizer variables
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var speechIntent: Intent
    private var isListening = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_send_form)

        // Initialize form views
        nurseIdInput = findViewById(R.id.nurseIdInput)
        nameInput = findViewById(R.id.nameInput)
        bloodGroupSpinner = findViewById(R.id.bloodGroupSpinner)
        genderSpinner = findViewById(R.id.genderSpinner)
        dobInput = findViewById(R.id.dobInput)
        bpInput = findViewById(R.id.bpInput)
        heartRateInput = findViewById(R.id.heartRateInput)
        respiratoryRateInput = findViewById(R.id.respiratoryRateInput)
        temperatureInput = findViewById(R.id.temperatureInput)
        generateButton = findViewById(R.id.generateButton)
        startVoiceBtn = findViewById(R.id.startVoiceBtn)

        // Initialize voice buttons
        voiceNurseIdBtn = findViewById(R.id.voiceNurseIdBtn)
        voiceNameBtn = findViewById(R.id.voiceNameBtn)
        voiceBpBtn = findViewById(R.id.voiceBpBtn)
        voiceHeartRateBtn = findViewById(R.id.voiceHeartRateBtn)
        voiceRespRateBtn = findViewById(R.id.voiceRespRateBtn)
        voiceTempBtn = findViewById(R.id.voiceTempBtn)

        // Initialize preview views
        formContainer = findViewById(R.id.formContainer)
        previewContainer = findViewById(R.id.previewContainer)
        fileContentText = findViewById(R.id.fileContentText)
        fileNameText = findViewById(R.id.fileNameText)
        editButton = findViewById(R.id.editButton)
        sendNfcButton = findViewById(R.id.sendNfcButton)

        // Setup voice enabled fields list (in order)
        voiceEnabledFields.clear()
        voiceEnabledFields.add(nurseIdInput)
        voiceEnabledFields.add(nameInput)
        voiceEnabledFields.add(bpInput)
        voiceEnabledFields.add(heartRateInput)
        voiceEnabledFields.add(respiratoryRateInput)
        voiceEnabledFields.add(temperatureInput)

        // Check and request permission
        checkAudioPermission()

        // Initialize Speech Recognizer
        setupSpeechRecognizer()

        // Setup spinners
        setupSpinners()

        // Setup date picker
        setupDatePicker()

        // Setup voice input buttons
        setupVoiceInputButtons()

        // Start voice input button (auto-fills all fields)
        startVoiceBtn.setOnClickListener {
            currentFieldIndex = 0
            startVoiceInputAtIndex(0)
        }

        generateButton.setOnClickListener { generateTxtFile() }
        editButton.setOnClickListener { showForm() }
        sendNfcButton.setOnClickListener { sendViaNfc() }
    }

    private fun setupSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer.setRecognitionListener(this)

            speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
        } else {
            Toast.makeText(this, "Speech recognition not supported on this device", Toast.LENGTH_LONG).show()
        }
    }

    private fun getFieldName(index: Int): String {
        return when (index) {
            0 -> "Nurse ID"
            1 -> "Name"
            2 -> "Blood Pressure"
            3 -> "Heart Rate"
            4 -> "Respiratory Rate"
            5 -> "Temperature"
            else -> "Field"
        }
    }

    private fun checkAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                RECORD_AUDIO_PERMISSION_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RECORD_AUDIO_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Microphone permission granted", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupVoiceInputButtons() {
        voiceNurseIdBtn.setOnClickListener { startVoiceInputAtIndex(0) }
        voiceNameBtn.setOnClickListener { startVoiceInputAtIndex(1) }
        voiceBpBtn.setOnClickListener { startVoiceInputAtIndex(2) }
        voiceHeartRateBtn.setOnClickListener { startVoiceInputAtIndex(3) }
        voiceRespRateBtn.setOnClickListener { startVoiceInputAtIndex(4) }
        voiceTempBtn.setOnClickListener { startVoiceInputAtIndex(5) }
    }

    private fun startVoiceInputAtIndex(index: Int) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            checkAudioPermission()
            return
        }

        if (index >= voiceEnabledFields.size) return

        currentFieldIndex = index

        // Visual Focus
        voiceEnabledFields[index].requestFocus()

        // Inform user to speak (since there is no popup)
        Toast.makeText(this, "Listening for ${getFieldName(index)}...", Toast.LENGTH_SHORT).show()

        try {
            speechRecognizer.startListening(speechIntent)
            isListening = true
        } catch (e: Exception) {
            Toast.makeText(this, "Error starting voice: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // --- RecognitionListener Methods ---

    override fun onReadyForSpeech(params: Bundle?) {
        // Optional: Change UI to show listening state (e.g., mic icon color)
    }

    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {
        isListening = false
    }

    override fun onError(error: Int) {
        isListening = false
        // Handle "No Match" silently or with a small toast, but don't crash
        if (error == SpeechRecognizer.ERROR_NO_MATCH) {
            Toast.makeText(this, "Didn't catch that, try again", Toast.LENGTH_SHORT).show()
        } else if (error != SpeechRecognizer.ERROR_CLIENT) {
            // ERROR_CLIENT happens when we cancel/stop, so ignore it
            // Toast.makeText(this, "Error: $error", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResults(results: Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!matches.isNullOrEmpty()) {
            val spokenText = matches[0]

            if (currentFieldIndex < voiceEnabledFields.size) {
                voiceEnabledFields[currentFieldIndex].setText(spokenText)
                Toast.makeText(this, "✓ ${getFieldName(currentFieldIndex)}: $spokenText", Toast.LENGTH_SHORT).show()

                // Move to next field automatically
                currentFieldIndex++

                if (currentFieldIndex < voiceEnabledFields.size) {
                    // Small delay before listening for the next field
                    Handler(Looper.getMainLooper()).postDelayed({
                        startVoiceInputAtIndex(currentFieldIndex)
                    }, 1000) // 1 second delay
                } else {
                    Toast.makeText(this, "✅ All fields completed!", Toast.LENGTH_LONG).show()
                    currentFieldIndex = 0
                }
            }
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    // --- End RecognitionListener ---

    private fun setupSpinners() {
        val bloodGroups = arrayOf("Select blood group", "A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-")
        val bloodGroupAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, bloodGroups)
        bloodGroupAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        bloodGroupSpinner.adapter = bloodGroupAdapter

        val genders = arrayOf("Select gender", "Male", "Female", "Other")
        val genderAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, genders)
        genderAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        genderSpinner.adapter = genderAdapter
    }

    private fun setupDatePicker() {
        dobInput.setOnClickListener {
            val calendar = Calendar.getInstance()
            DatePickerDialog(
                this,
                { _, y, m, d ->
                    val formattedDate = String.format("%02d/%02d/%04d", d, m + 1, y)
                    dobInput.setText(formattedDate)
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
            ).show()
        }
    }

    private fun generateTxtFile() {
        val name = nameInput.text.toString().trim()
        val nurseId = nurseIdInput.text.toString().trim()

        if (name.isEmpty()) {
            Toast.makeText(this, "Name is required!", Toast.LENGTH_SHORT).show()
            nameInput.error = "Name cannot be empty"
            return
        }

        val bloodGroup = if (bloodGroupSpinner.selectedItemPosition > 0) bloodGroupSpinner.selectedItem.toString() else ""
        val gender = if (genderSpinner.selectedItemPosition > 0) genderSpinner.selectedItem.toString() else ""
        val dob = dobInput.text.toString().trim()
        val bp = bpInput.text.toString().trim()
        val heartRate = heartRateInput.text.toString().trim()
        val respiratoryRate = respiratoryRateInput.text.toString().trim()
        val temperature = temperatureInput.text.toString().trim()

        val content = StringBuilder()
        content.append("MEDICAL INFORMATION\n==================\n\n")
        content.append("Nurse ID: $nurseId\n")
        content.append("Name: $name\n")
        if (bloodGroup.isNotEmpty()) content.append("Blood Group: $bloodGroup\n")
        if (gender.isNotEmpty()) content.append("Gender: $gender\n")
        if (dob.isNotEmpty()) content.append("Date of Birth: $dob\n")
        if (bp.isNotEmpty()) content.append("Blood Pressure: $bp\n")
        if (heartRate.isNotEmpty()) content.append("Heart Rate: $heartRate bpm\n")
        if (respiratoryRate.isNotEmpty()) content.append("Respiratory Rate: $respiratoryRate breaths/min\n")
        if (temperature.isNotEmpty()) content.append("Body Temperature: ${temperature}°F\n")
        content.append("\n==================\n")
        content.append("Generated on: ${SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())}\n")

        fileContent = content.toString()
        val fileName = "medical_data_${name.replace(" ", "_")}.txt"

        try {
            generatedFile = File(cacheDir, fileName)
            generatedFile?.writeText(fileContent)
            showPreview(fileName)
            Toast.makeText(this, "File generated successfully!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error generating file: ${e.message}", Toast.LENGTH_SHORT).show()
            e.printStackTrace()
        }
    }

    private fun showPreview(fileName: String) {
        formContainer.visibility = View.GONE
        previewContainer.visibility = View.VISIBLE
        fileContentText.text = fileContent
        fileNameText.text = "File name: $fileName"
    }

    private fun showForm() {
        previewContainer.visibility = View.GONE
        formContainer.visibility = View.VISIBLE
    }

    private fun sendViaNfc() {
        if (generatedFile == null) {
            Toast.makeText(this, "No file to send!", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, SendDocumentActivity::class.java)
        intent.putExtra("FILE_PATH", generatedFile?.absolutePath)
        intent.putExtra("FILE_NAME", generatedFile?.name)
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            speechRecognizer.destroy()
        } catch (e: Exception) {
            // handle clean up error
        }
    }
}