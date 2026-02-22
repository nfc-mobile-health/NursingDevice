package com.example.nursingdevice

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import java.nio.ByteBuffer
import java.util.Arrays
import kotlin.math.min
import com.example.nursingdevice.CryptoUtils

data class FileData(val name: String, val content: ByteArray)

class MyHostApduService : HostApduService() {

    // --- Instance State Management ---
    private var transferMode = "NONE" // Modes: "NONE", "TEXT", "FILE", "MULTI_FILE"
    private var textContent: String? = null
    private var fileContent: ByteArray? = null
    private var fileMimeType: String? = null
    private var fileChunkOffset: Int = 0
    private var singleAppendedFile: ByteArray? = null
    private var currentFileName: String = "appended_data.dat"

    private var tempEncryptedKey: ByteArray? = null
    private var sessionKey: ByteArray? = null
    private var isAuthenticated = false

    companion object {

        // --- Class-level static references for sending operations ---
        private var sharedTransferMode = "NONE"
        private var sharedTextContent: String? = null
        private var sharedFileContent: ByteArray? = null
        private var sharedFileMimeType: String? = null
        private var sharedAppendedFile: ByteArray? = null
        private var sharedCurrentFileName: String = "appended_data.dat"

        fun setTextForTransfer(text: String) {
            sharedTransferMode = "TEXT"
            sharedTextContent = text
            sharedFileContent = null
            sharedFileMimeType = null
            Log.d("HCE_SERVICE", "Service armed for TEXT transfer.")
        }

        fun setFileForTransfer(content: ByteArray, mimeType: String) {
            sharedTransferMode = "FILE"
            sharedFileContent = content
            sharedFileMimeType = mimeType
            sharedTextContent = null
            Log.d("HCE_SERVICE", "Service armed for FILE transfer. Size: ${content.size}")
        }

        fun setSingleAppendedFileForTransfer(fileData: ByteArray, fileName: String = "appended_data.dat") {
            sharedTransferMode = "MULTI_FILE"
            sharedAppendedFile = fileData
            sharedCurrentFileName = fileName
            sharedTextContent = null
            sharedFileContent = null
            sharedFileMimeType = null
            Log.d("HCE_SERVICE", "Service armed for APPENDED FILE transfer. File size: ${fileData.size}, Name: $fileName")
        }

        fun resetTransferState() {
            sharedTransferMode = "NONE"
            sharedTextContent = null
            sharedFileContent = null
            sharedFileMimeType = null
            sharedAppendedFile = null
            sharedCurrentFileName = "appended_data.dat"
        }
    }

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray {
        // 1. Reset State on Application Select
        if (Arrays.equals(commandApdu, Utils.SELECT_APD)) {
            Log.d("HCE", "App Selected. Resetting Auth.")
            isAuthenticated = false
            sessionKey = null
            tempEncryptedKey = null

            // Sync with shared state (Prepare files)
            transferMode = sharedTransferMode
            textContent = sharedTextContent
            fileContent = sharedFileContent
            fileMimeType = sharedFileMimeType

            return Utils.SELECT_OK_SW
        }

        // 2. Auth Step 1: Receive Encrypted Session Key
        // Protocol: [CMD (8 bytes)] + [Encrypted Key (256 bytes)]
        if (commandApdu.size > 8 && Arrays.equals(commandApdu.take(8).toByteArray(), CryptoUtils.CMD_AUTH_SEND_KEY)) {
            tempEncryptedKey = commandApdu.copyOfRange(8, commandApdu.size)
            Log.d("HCE", "Auth Step 1: Received Encrypted Key.")
            return Utils.SELECT_OK_SW
        }

        // 3. Auth Step 2: Receive Signature & Verify
        // Protocol: [CMD (8 bytes)] + [Signature (256 bytes)]
        if (commandApdu.size > 8 && Arrays.equals(commandApdu.take(8).toByteArray(), CryptoUtils.CMD_AUTH_SEND_SIG)) {
            val signature = commandApdu.copyOfRange(8, commandApdu.size)

            if (tempEncryptedKey == null) {
                Log.e("HCE", "Auth Fail: Signature received without Key.")
                return Utils.FILE_NOT_READY_SW
            }

            try {
                // A. Verify Signature (Did Aggregator send this?)
                val isValid = CryptoUtils.rsaVerify(
                    tempEncryptedKey!!,
                    signature,
                    CryptoUtils.getOtherPublicKey() // Aggregator's Public Key
                )

                if (isValid) {
                    // B. Decrypt Session Key (Is it meant for me?)
                    sessionKey = CryptoUtils.rsaDecrypt(
                        tempEncryptedKey!!,
                        CryptoUtils.getMyPrivateKey() // My Private Key
                    )
                    isAuthenticated = true
                    Log.d("HCE", "Auth Step 2: Signature Valid. Session Secured.")

                    // C. Return Encrypted ACK
                    val ack = "AUTH_OK".toByteArray(Charsets.UTF_8)
                    val encryptedAck = CryptoUtils.xorEncryptDecrypt(ack, sessionKey!!)
                    return Utils.concatArrays(encryptedAck, Utils.SELECT_OK_SW)
                } else {
                    Log.e("HCE", "Signature Verification Failed!")
                }
            } catch (e: Exception) {
                Log.e("HCE", "Auth Error: ${e.message}")
            }
            return Utils.FILE_NOT_READY_SW
        }

        // 4. Security Check: Block all other commands if not authenticated
        if (!isAuthenticated || sessionKey == null) {
            Log.w("HCE", "Access Denied: Not Authenticated")
            return Utils.FILE_NOT_READY_SW
        }

        // 5. Process Data Requests (Normal Logic)
        val response = when (transferMode) {
            "TEXT" -> handleTextTransfer(commandApdu)
            "FILE" -> handleFileTransfer(commandApdu)
            "MULTI_FILE" -> handleAppendedFileTransfer(commandApdu)
            else -> Utils.FILE_NOT_READY_SW
        }

        // 6. Encrypt Response Data
        if (response.size >= 2 && Arrays.equals(response.takeLast(2).toByteArray(), Utils.SELECT_OK_SW)) {
            val rawData = response.copyOfRange(0, response.size - 2)
            val encryptedData = CryptoUtils.xorEncryptDecrypt(rawData, sessionKey!!)
            Log.d("HCE", "Encrypting response: ${rawData.size} bytes -> ${encryptedData.size} bytes")
            return Utils.concatArrays(encryptedData, Utils.SELECT_OK_SW)
        }

        return response
    }

    private fun handleTextTransfer(commandApdu: ByteArray): ByteArray {
        if (!Arrays.equals(commandApdu, Utils.GET_FILE_INFO_COMMAND)) {
            return Utils.UNKNOWN_CMD_SW
        }

        val text = textContent ?: return Utils.FILE_NOT_READY_SW
        val textBytes = text.toByteArray(Charsets.UTF_8)

        // Metadata Payload for Text: [1 byte for mode ('T')] + [N bytes for the text itself]
        val modeByte = "T".toByteArray(Charsets.UTF_8)
        val textPayload = Utils.concatArrays(modeByte, textBytes)

        Log.d("HCE_SERVICE", "Sending text payload of size ${textPayload.size}")
        return Utils.concatArrays(textPayload, Utils.SELECT_OK_SW)
    }

    private fun handleFileTransfer(commandApdu: ByteArray): ByteArray {
        return when {
            Arrays.equals(commandApdu, Utils.GET_FILE_INFO_COMMAND) -> {
                val content = fileContent ?: return Utils.FILE_NOT_READY_SW
                val mimeBytes = fileMimeType?.toByteArray(Charsets.UTF_8) ?: return Utils.FILE_NOT_READY_SW

                // Metadata Payload for File: [1 byte for mode ('F')] + [4 bytes for size] + [N bytes for MIME]
                val modeByte = "F".toByteArray(Charsets.UTF_8)
                val sizeBytes = ByteBuffer.allocate(4).putInt(content.size).array()
                val fileInfoPayload = Utils.concatArrays(modeByte, sizeBytes, mimeBytes)

                Log.d("HCE_SERVICE", "Sending file metadata payload.")
                return Utils.concatArrays(fileInfoPayload, Utils.SELECT_OK_SW)
            }

            Arrays.equals(commandApdu, Utils.GET_NEXT_DATA_CHUNK_COMMAND) -> {
                val content = fileContent ?: return Utils.FILE_NOT_READY_SW
                val remaining = content.size - fileChunkOffset
                if (remaining <= 0) return Utils.FILE_NOT_READY_SW

                val chunkSize = min(remaining, 245)
                val chunk = content.copyOfRange(fileChunkOffset, fileChunkOffset + chunkSize)
                fileChunkOffset += chunkSize

                return Utils.concatArrays(chunk, Utils.SELECT_OK_SW)
            }

            else -> Utils.UNKNOWN_CMD_SW
        }
    }

    private fun handleAppendedFileTransfer(commandApdu: ByteArray): ByteArray {
        return when {
            Arrays.equals(commandApdu, Utils.GET_FILE_INFO_COMMAND) -> {
                val fileData = singleAppendedFile ?: return Utils.FILE_NOT_READY_SW

                // Metadata format: [1 byte 'M'] + [4 bytes file size] + [N bytes filename]
                val modeByte = "M".toByteArray(Charsets.UTF_8)
                val sizeBytes = ByteBuffer.allocate(4).putInt(fileData.size).array()
                val fileNameBytes = currentFileName.toByteArray(Charsets.UTF_8)

                val fileInfoPayload = Utils.concatArrays(modeByte, sizeBytes, fileNameBytes)
                fileChunkOffset = 0

                Log.d("HCE_SERVICE", "Sending appended file metadata: $currentFileName, Size: ${fileData.size}")
                return Utils.concatArrays(fileInfoPayload, Utils.SELECT_OK_SW)
            }

            Arrays.equals(commandApdu, Utils.GET_NEXT_DATA_CHUNK_COMMAND) -> {
                val fileData = singleAppendedFile ?: return Utils.FILE_NOT_READY_SW
                val remaining = fileData.size - fileChunkOffset

                if (remaining <= 0) {
                    Log.d("HCE_SERVICE", "Appended file transfer complete.")
                    return Utils.FILE_NOT_READY_SW
                }

                val chunkSize = min(remaining, 245)
                val chunk = fileData.copyOfRange(fileChunkOffset, fileChunkOffset + chunkSize)
                fileChunkOffset += chunkSize

                Log.d("HCE_SERVICE", "Sending chunk: $fileChunkOffset / ${fileData.size}")
                return Utils.concatArrays(chunk, Utils.SELECT_OK_SW)
            }

            else -> Utils.UNKNOWN_CMD_SW
        }
    }

    override fun onDeactivated(reason: Int) {
        Log.d("HCE_SERVICE", "Deactivated (reason: $reason).")
        if (reason == DEACTIVATION_LINK_LOSS) {
            Log.d("HCE_SERVICE", "Link loss - keeping data for reconnection")
        } else {
            Log.d("HCE_SERVICE", "Major deactivation - resetting state")
            resetTransferState()
        }
    }
}