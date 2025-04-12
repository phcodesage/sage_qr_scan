package com.example.sage_qr_scan

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.sage_qr_scan.databinding.FragmentFirstBinding
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * A Fragment for scanning QR codes using CameraX and ML Kit.
 */
class FirstFragment : Fragment() {

    private var _binding: FragmentFirstBinding? = null
    private val binding get() = _binding!!

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var barcodeScanner: BarcodeScanner? = null
    private var isScanning = false
    
    // Camera control properties
    private var flashEnabled = false
    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(
                requireContext(),
                "Camera permission is required to scan QR codes",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFirstBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Setup barcode scanner with options for QR codes
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        barcodeScanner = BarcodeScanning.getClient(options)

        // Set initial button text to "Scan QR"
        binding.scanButton.text = "Scan QR"

        // Check camera permission and start camera if granted
        binding.scanButton.setOnClickListener {
            if (ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                toggleScanning()
            } else {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        // Setup copy to clipboard functionality
        binding.copyButton.setOnClickListener {
            val text = binding.qrResultText.text.toString()
            if (text != "No QR code scanned yet") {
                val clipboardManager = requireActivity().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clipData = ClipData.newPlainText("QR Code Content", text)
                clipboardManager.setPrimaryClip(clipData)
                Toast.makeText(requireContext(), "Text copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun toggleScanning() {
        isScanning = !isScanning
        if (isScanning) {
            binding.scanButton.text = "Stop Scanning"
            startCamera()
            // Reset result text when starting a new scan
            if (binding.qrResultText.text.toString() != "No QR code scanned yet") {
                binding.qrResultText.text = "Scanning for QR code..."
                binding.copyButton.isEnabled = false
            }
        } else {
            binding.scanButton.text = "Scan QR"
            stopCamera()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start camera", e)
                Toast.makeText(
                    requireContext(),
                    "Failed to start camera: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun stopCamera() {
        cameraProvider?.unbindAll()
    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: return

        // Setup the preview use case
        preview = Preview.Builder().build().also {
            it.setSurfaceProvider(binding.previewView.surfaceProvider)
        }

        // Setup image analysis use case
        imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(android.util.Size(1280, 720))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(cameraExecutor) { imageProxy ->
                    processImageProxy(imageProxy)
                }
            }

        try {
            // Unbind any bound use cases before rebinding
            cameraProvider.unbindAll()
            // Bind use cases to camera
            camera = cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalysis
            )
            
            // Apply flash mode if camera supports it
            camera?.let { camera ->
                if (camera.cameraInfo.hasFlashUnit()) {
                    camera.cameraControl.enableTorch(flashEnabled)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Use case binding failed", e)
        }
    }
    
    /**
     * Toggle the camera flash on/off
     */
    fun toggleFlash() {
        camera?.let { camera ->
            if (camera.cameraInfo.hasFlashUnit()) {
                flashEnabled = !flashEnabled
                camera.cameraControl.enableTorch(flashEnabled)
                
                // Show toast to indicate flash state
                val message = if (flashEnabled) "Flash ON" else "Flash OFF"
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "Flash not available on this device", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * Switch between front and back cameras
     */
    fun switchCamera() {
        cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        
        // Rebind use cases with new camera selector
        if (isScanning) {
            bindCameraUseCases()
            
            // Show toast to indicate camera switch
            val cameraMessage = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) "Back Camera" else "Front Camera"
            Toast.makeText(requireContext(), cameraMessage, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(requireContext(), "Start scanning to switch camera", Toast.LENGTH_SHORT).show()
        }
    }

    private fun processImageProxy(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image ?: return

        val image = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees
        )

        barcodeScanner?.process(image)
            ?.addOnSuccessListener { barcodes ->
                if (barcodes.isNotEmpty()) {
                    val barcode = barcodes[0]
                    barcode.rawValue?.let { qrContent ->
                        requireActivity().runOnUiThread {
                            binding.qrResultText.text = qrContent
                            // Enable copy button when QR code is detected
                            binding.copyButton.isEnabled = true
                            // Auto-stop scanning after successful detection
                            if (isScanning) {
                                toggleScanning()
                                Toast.makeText(requireContext(), "QR code detected!", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
            ?.addOnFailureListener { e ->
                Log.e(TAG, "Barcode scanning failed", e)
            }
            ?.addOnCompleteListener {
                imageProxy.close()
            }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor.shutdown()
        barcodeScanner?.close()
        _binding = null
    }

    companion object {
        private const val TAG = "FirstFragment"
    }
}