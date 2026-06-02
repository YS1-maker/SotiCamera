package com.soti.soticamera

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.soti.soticamera.databinding.ActivityMainBinding
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    companion object {
        // Replace with a GitHub Personal Access Token that has `repo` scope
        private const val GITHUB_TOKEN = "YOUR_GITHUB_TOKEN"
        private const val GITHUB_REPO = "YS1-maker/SotiCamera"
        private const val GITHUB_BRANCH = "main"
        private const val TAG = "SotiCamera"
    }

    private lateinit var binding: ActivityMainBinding
    private var imageCapture: ImageCapture? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission is required.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        checkPermissionsAndStart()
    }

    private fun checkPermissionsAndStart() {
        val cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        val storageGranted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED

        if (cameraGranted && storageGranted) {
            startCamera()
        } else {
            val perms = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                arrayOf(Manifest.permission.CAMERA)
            }
            permissionLauncher.launch(perms)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    imageCapture
                )
                // Give the camera sensor 800 ms to stabilise before capturing
                binding.root.postDelayed({ captureImage() }, 800)
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to open camera: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun getExistingFileSha(filename: String): String? {
        return try {
            val url = URL("https://api.github.com/repos/$GITHUB_REPO/contents/images/$filename")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Authorization", "Bearer $GITHUB_TOKEN")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            val code = conn.responseCode
            val sha = if (code == HttpURLConnection.HTTP_OK) {
                val response = conn.inputStream.bufferedReader().readText()
                org.json.JSONObject(response).optString("sha").takeIf { it.isNotEmpty() }
            } else null
            conn.disconnect()
            sha
        } catch (e: Exception) {
            Log.e(TAG, "SHA fetch error: ${e.message}")
            null
        }
    }

    private fun uploadToGitHub(imageBytes: ByteArray, filename: String) {
        try {
            val base64Content = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
            val sha = getExistingFileSha(filename)
            val shaField = if (sha != null) ",\"sha\":\"$sha\"" else ""
            val jsonBody = "{\"message\":\"Upload $filename\",\"content\":\"$base64Content\",\"branch\":\"$GITHUB_BRANCH\"$shaField}"

            val url = URL("https://api.github.com/repos/$GITHUB_REPO/contents/images/$filename")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "PUT"
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("Authorization", "Bearer $GITHUB_TOKEN")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            conn.doOutput = true

            val bodyBytes = jsonBody.toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(bodyBytes.size)
            conn.outputStream.use { it.write(bodyBytes) }

            val responseCode = conn.responseCode
            if (responseCode == HttpURLConnection.HTTP_CREATED || responseCode == HttpURLConnection.HTTP_OK) {
                Log.d(TAG, "GitHub upload success ($responseCode): $filename")
            } else {
                val error = conn.errorStream?.bufferedReader()?.readText()
                Log.e(TAG, "GitHub upload failed ($responseCode): $error")
            }
            conn.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "GitHub upload error: ${e.message}", e)
        }
    }

    // Deletes all SOTI images from the device before capturing a new one.
    private fun deleteAllSotiImages() {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Images.Media.RELATIVE_PATH} = ?"
        } else {
            "${MediaStore.Images.Media.DATA} LIKE ?"
        }
        val selectionArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf("Pictures/SOTI/")
        } else {
            arrayOf("%/SOTI/%")
        }

        contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                contentResolver.delete(ContentUris.withAppendedId(collection, id), null, null)
            }
        }
    }

    private fun captureImage() {
        val capture = imageCapture ?: return

        // Delete old BG1.jpg before saving so MediaStore uses the exact name, not BG1(1).jpg
        deleteAllSotiImages()

        val filename = "BG1.jpg"

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SOTI")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            .build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val savedUri = output.savedUri ?: return

                    // Upload to GitHub in background so UI is not blocked
                    val appContentResolver = applicationContext.contentResolver
                    Thread {
                        val imageBytes = appContentResolver.openInputStream(savedUri)?.readBytes()
                        if (imageBytes != null) {
                            uploadToGitHub(imageBytes, filename)
                        }
                    }.start()

                    val intent = Intent(this@MainActivity, FullScreenActivity::class.java).apply {
                        putExtra(FullScreenActivity.EXTRA_IMAGE_URI, savedUri.toString())
                    }
                    startActivity(intent)
                    finish()
                }

                override fun onError(exc: ImageCaptureException) {
                    Toast.makeText(
                        this@MainActivity,
                        "Capture failed: ${exc.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }
}
