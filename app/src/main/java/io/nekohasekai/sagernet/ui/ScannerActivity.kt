package app.qcoldthrone.mobile.ui

import android.Manifest
import android.content.Intent
import android.content.pm.ShortcutManager
import android.content.pm.PackageManager
import android.graphics.ImageDecoder
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.getSystemService
import androidx.core.view.isVisible
import com.google.zxing.Result
import com.king.zxing.CameraScan
import com.king.zxing.DefaultCameraScan
import com.king.zxing.analyze.QRCodeAnalyzer
import com.king.zxing.util.CodeUtils
import com.king.zxing.util.LogUtils
import com.king.zxing.util.PermissionUtils
import app.qcoldthrone.mobile.R
import app.qcoldthrone.mobile.databinding.LayoutScannerBinding
import app.qcoldthrone.mobile.ktx.*
import app.qcoldthrone.mobile.widget.applyInsetMargin
import java.util.concurrent.atomic.AtomicBoolean


class ScannerActivity : ThemedActivity(),
    CameraScan.OnScanResultCallback {

    lateinit var binding: LayoutScannerBinding
    lateinit var cameraScan: CameraScan

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 25) getSystemService<ShortcutManager>()!!.reportShortcutUsed("scan")
        binding = LayoutScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.ivFlashlight.applyInsetMargin(bottom = true, horizontal = true)
        binding.ivPhotoLibrary.applyInsetMargin(bottom = true, horizontal = true)
        binding.ivPhotoLibrary.setOnClickListener {
            startFilesForResult(importCodeFile, "image/*")
        }

        // Without a camera (TVs) the images are the only source: straight to the picker.
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            binding.ivFlashlight.isVisible = false
            if (savedInstanceState == null) {
                Toast.makeText(this, R.string.scanner_no_camera, Toast.LENGTH_LONG).show()
                startFilesForResult(importCodeFile, "image/*")
            }
            return
        }

        initCameraScan()
        startCamera()
        binding.ivFlashlight.setOnClickListener { toggleTorchState() }
    }

    /** Images from the gallery: every QR code found goes to the main window in one batch (importFromFiles). */
    val importCodeFile = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        runOnDefaultDispatcher {
            val texts = ArrayList<String>()
            try {
                uris.forEachTry { uri ->
                    val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        ImageDecoder.decodeBitmap(
                            ImageDecoder.createSource(
                                contentResolver, uri
                            )
                        ) { decoder, _, _ ->
                            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                            decoder.isMutableRequired = true
                        }
                    } else {
                        @Suppress("DEPRECATION") MediaStore.Images.Media.getBitmap(
                            contentResolver, uri
                        )
                    }
                    CodeUtils.parseCodeResult(bitmap)?.text?.takeIf { it.isNotBlank() }?.let(texts::add)
                }
            } catch (e: Exception) {
                Logs.w(e)
                onMainDispatcher {
                    Toast.makeText(app, e.readableMessage, Toast.LENGTH_LONG).show()
                }
            }
            onMainDispatcher {
                if (texts.isNotEmpty()) {
                    importTexts(texts)
                } else if (uris.isNotEmpty()) {
                    Toast.makeText(app, R.string.action_import_err, Toast.LENGTH_SHORT).show()
                }
                if (uris.isNotEmpty() || !::cameraScan.isInitialized) finish()
            }
        }
    }

    var finished = AtomicBoolean(false)

    /** @return true when the result is consumed (no further processing), false to continue. */
    override fun onScanResultCallback(result: Result?): Boolean {
        if (finished.getAndSet(true)) return true
        val text = result?.text
        if (text.isNullOrBlank()) {
            Toast.makeText(app, R.string.action_import_err, Toast.LENGTH_SHORT).show()
        } else {
            importTexts(listOf(text))
        }
        finish()
        return true
    }

    /** The main window imports them (SubscribeFlows): the URL choice, deep links, or profiles into the current group. */
    private fun importTexts(texts: List<String>) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            putStringArrayListExtra(MainActivity.EXTRA_IMPORT_TEXTS, ArrayList(texts))
        })
    }

    fun initCameraScan() {
        cameraScan = DefaultCameraScan(this, binding.previewView)
        cameraScan.setAnalyzer(QRCodeAnalyzer())
        cameraScan.setOnScanResultCallback(this)
        cameraScan.setNeedAutoZoom(true)
    }

    fun startCamera() {
        if (PermissionUtils.checkPermission(this, Manifest.permission.CAMERA)) {
            cameraScan.startCamera()
        } else {
            LogUtils.d("checkPermissionResult != PERMISSION_GRANTED")
            PermissionUtils.requestPermission(
                this, Manifest.permission.CAMERA, CAMERA_PERMISSION_REQUEST_CODE
            )
        }
    }

    private fun releaseCamera() {
        if (::cameraScan.isInitialized) cameraScan.release()
    }

    protected fun toggleTorchState() {
        val isTorch = cameraScan.isTorchEnabled
        cameraScan.enableTorch(!isTorch)
        binding.ivFlashlight.isSelected = !isTorch
    }

    val CAMERA_PERMISSION_REQUEST_CODE = 0X86

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            requestCameraPermissionResult(permissions, grantResults)
        }
    }

    fun requestCameraPermissionResult(permissions: Array<String>, grantResults: IntArray) {
        if (PermissionUtils.requestPermissionsResult(
                Manifest.permission.CAMERA, permissions, grantResults
            )
        ) {
            startCamera()
        } else {
            finish()
        }
    }

    override fun onDestroy() {
        releaseCamera()
        super.onDestroy()
    }
}
