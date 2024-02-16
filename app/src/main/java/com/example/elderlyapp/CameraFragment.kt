package com.example.elderlyapp

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import kotlinx.coroutines.*
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.image.TensorImage
import java.nio.MappedByteBuffer
import java.io.FileInputStream
import java.nio.channels.FileChannel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.content.res.AssetManager
import androidx.activity.result.contract.ActivityResultContracts
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CameraFragment : Fragment() {
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var previewView: PreviewView
    private var currentImage: Bitmap? = null

    companion object {
        private const val TAG = "CameraFragment"
        private const val requestImageCapture = 1
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        previewView = view.findViewById(R.id.preview_view)
        val capturedImage: ImageView = view.findViewById(R.id.captured_image)
        cameraExecutor = Executors.newSingleThreadExecutor()

        val captureImageButton: Button = view.findViewById(R.id.capture_image_button)

        val resultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val imageBitmap = result.data?.extras?.get("data") as Bitmap
                capturedImage.setImageBitmap(imageBitmap)
                currentImage = imageBitmap
                // Make the ImageView visible
                capturedImage.visibility = View.VISIBLE
                // Make the PreviewView gone
                previewView.visibility = View.GONE
            }
        }


        captureImageButton.setOnClickListener {
            val takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            resultLauncher.launch(takePictureIntent)
        }

        val liveButton: Button = view.findViewById(R.id.live_button)
        liveButton.setOnClickListener {
            capturedImage.visibility = View.GONE
            previewView.visibility = View.VISIBLE
        }

        startCamera()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_camera, container, false)

        val captureImageButton: Button = view.findViewById(R.id.capture_image_button)
        captureImageButton.setOnClickListener {
            dispatchTakePictureIntent()
        }

        val clearImageButton: Button = view.findViewById(R.id.clear_image_button)
        clearImageButton.setOnClickListener {
            val capturedImage: ImageView = view.findViewById(R.id.captured_image)
            capturedImage.setImageBitmap(null)
            currentImage = null
        }

        val submitButton: Button = view.findViewById(R.id.submit_button)
        submitButton.setOnClickListener {
            currentImage?.let { image ->
                sendImageToModel(image)
            }
        }

        return view
    }

    private fun dispatchTakePictureIntent() {
        Intent(MediaStore.ACTION_IMAGE_CAPTURE).also { takePictureIntent ->
            takePictureIntent.resolveActivity(requireActivity().packageManager)?.also {
                startActivityForResult(takePictureIntent, requestImageCapture)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == requestImageCapture && resultCode == Activity.RESULT_OK) {
            val imageBitmap = data?.extras?.get("data") as Bitmap
            val capturedImage: ImageView = requireView().findViewById(R.id.captured_image)
            capturedImage.setImageBitmap(imageBitmap)
            currentImage = imageBitmap
        }
    }

    private fun sendImageToModel(bitmap: Bitmap) {
        CoroutineScope(Dispatchers.IO).launch {
            // Resize the image to match your model's input dimensions
            val resizedImage = Bitmap.createScaledBitmap(bitmap, /*width=*/1088, /*height=*/608, /*filter=*/false)

            // Convert the resized image to a ByteBuffer
            val byteBuffer = convertBitmapToByteBuffer(resizedImage)

            // Load the model
            val assetManager = requireContext().assets
            val model = Interpreter(loadModelFile(assetManager, "model.tflite"))

            // Run the inference
            val output = Array(1) { Array(9) { FloatArray(13566) } } // Adjust the size based on your model's output
            model.run(byteBuffer, output)

            // Log the model's output
            Log.d("Model Output", output.contentDeepToString())

            // Postprocess the output and update the UI
            val predictedIndex = output[0][0].withIndex().maxByOrNull { it.value }?.index
            val confidence = output[0][0].maxOrNull()!!

            // Update the UI on the main thread
            withContext(Dispatchers.Main) {
                val predictionText: TextView = requireView().findViewById(R.id.prediction_text)
                predictionText.text = "Medicine: $predictedIndex"
                val confidenceText: TextView = requireView().findViewById(R.id.result_text)
                confidenceText.text = "Confidence: ${"%.2f".format(confidence * 100)}"
            }

            // Close the model to free up resources
            model.close()
        }
    }


    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(4 * 608 * 1088 * 3) // float32 image with dimensions 608x1088x3
        byteBuffer.order(ByteOrder.nativeOrder())
        val intValues = IntArray(608 * 1088)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var pixel = 0
        for (i in 0 until 608) {
            for (j in 0 until 1088) {
                val value = intValues[pixel++]

                byteBuffer.putFloat(((value shr 16 and 0xFF) - 127.5f) / 127.5f)
                byteBuffer.putFloat(((value shr 8 and 0xFF) - 127.5f) / 127.5f)
                byteBuffer.putFloat(((value and 0xFF) - 127.5f) / 127.5f)
            }
        }
        return byteBuffer
    }

    private fun loadModelFile(assetManager: AssetManager, modelPath: String): MappedByteBuffer {
        val fileDescriptor = assetManager.openFd(modelPath)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()

                cameraProvider.bindToLifecycle(
                    this as LifecycleOwner, cameraSelector, preview)

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(requireContext()))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor.shutdown()
    }
}
