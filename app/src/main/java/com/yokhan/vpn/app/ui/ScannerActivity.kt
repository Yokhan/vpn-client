package com.yokhan.vpn.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.ShortcutManager
import android.graphics.ImageDecoder
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import com.google.zxing.Result
import com.king.zxing.CameraScan
import com.king.zxing.DefaultCameraScan
import com.king.zxing.ViewfinderView
import com.king.zxing.analyze.QRCodeAnalyzer
import com.king.zxing.util.CodeUtils
import com.king.zxing.util.LogUtils
import com.king.zxing.util.PermissionUtils
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.SubscriptionFoundException
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.forEachTry
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.startFilesForResult
import com.yokhan.vpn.app.ui.compose.YokhanButtonGrid
import com.yokhan.vpn.app.ui.compose.YokhanRouteAction
import com.yokhan.vpn.app.ui.compose.YokhanRouteSurface
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ScannerActivity : ThemedActivity(), CameraScan.OnScanResultCallback {

    private lateinit var cameraScan: CameraScan
    private val torchEnabled = mutableStateOf(false)

    private val importCodeFile = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) {
        runOnDefaultDispatcher {
            try {
                it.forEachTry { uri ->
                    val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        ImageDecoder.decodeBitmap(
                            ImageDecoder.createSource(contentResolver, uri),
                        ) { decoder, _, _ ->
                            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                            decoder.isMutableRequired = true
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        MediaStore.Images.Media.getBitmap(contentResolver, uri)
                    }
                    val result = CodeUtils.parseCodeResult(bitmap)
                    onMainDispatcher {
                        onScanResultCallback(result, true)
                    }
                }
                finish()
            } catch (e: Exception) {
                Logs.w(e)
                onMainDispatcher {
                    Toast.makeText(app, e.readableMessage, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private val finished = AtomicBoolean(false)
    private val importedN = AtomicInteger(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 25) {
            getSystemService<ShortcutManager>()!!.reportShortcutUsed("scan")
        }
        setContent {
            Content()
        }
    }

    @Composable
    private fun Content() {
        YokhanRouteSurface(
            title = stringResource(R.string.add_profile_methods_scan_qr_code),
            systemLabel = stringResource(R.string.dashboard_system_label),
            spaceActive = DataStore.serviceState.isSpaceActive(),
            onMenuClick = ::finish,
        ) { colors, typography ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
            ) {
                AndroidView(
                    factory = { context ->
                        PreviewView(context).also { preview ->
                            initCameraScan(preview)
                            startCamera()
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                AndroidView(
                    factory = { context -> ViewfinderView(context) },
                    modifier = Modifier.fillMaxSize(),
                )
                AndroidView(
                    factory = { context ->
                        ImageView(context).apply {
                            setImageResource(R.drawable.zxl_flashlight_selector)
                            isClickable = true
                            isFocusable = true
                            setOnClickListener { toggleTorchState() }
                        }
                    },
                    update = { image -> image.isSelected = torchEnabled.value },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(top = dimensionResource(R.dimen.zxl_flashlight_margin_top)),
                )
                YokhanButtonGrid(
                    actions = listOf(
                        YokhanRouteAction(
                            title = stringResource(R.string.action_import_file),
                            primary = true,
                            onClick = { startFilesForResult(importCodeFile, "image/*") },
                        ),
                    ),
                    colors = colors,
                    typography = typography,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 24.dp, vertical = 28.dp),
                )
            }
        }
    }

    override fun onScanResultCallback(result: Result?): Boolean {
        return onScanResultCallback(result, false)
    }

    private fun onScanResultCallback(result: Result?, multi: Boolean): Boolean {
        if (!multi && finished.getAndSet(true)) return true
        if (!multi) finish()
        runOnDefaultDispatcher {
            try {
                val text = result?.text ?: throw Exception("QR code not found")
                val results = RawUpdater.parseRaw(text)
                if (!results.isNullOrEmpty()) {
                    val currentGroupId = DataStore.ensureSelectedGroupForImport()
                    if (DataStore.selectedGroup != currentGroupId) {
                        DataStore.selectedGroup = currentGroupId
                    }

                    for (profile in results) {
                        ProfileManager.createProfile(currentGroupId, profile)
                        importedN.addAndGet(1)
                    }
                } else {
                    onMainDispatcher {
                        Toast.makeText(app, R.string.action_import_err, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: SubscriptionFoundException) {
                startActivity(Intent(this@ScannerActivity, MainActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    data = e.link.toUri()
                })
            } catch (e: Throwable) {
                Logs.w(e)
                onMainDispatcher {
                    var text = getString(R.string.action_import_err)
                    text += "\n" + e.readableMessage
                    Toast.makeText(app, text, Toast.LENGTH_SHORT).show()
                }
            }
        }
        return true
    }

    private fun initCameraScan(previewView: PreviewView) {
        if (::cameraScan.isInitialized) return
        cameraScan = DefaultCameraScan(this, previewView)
        cameraScan.setAnalyzer(QRCodeAnalyzer())
        cameraScan.setOnScanResultCallback(this)
        cameraScan.setNeedAutoZoom(true)
    }

    private fun startCamera() {
        if (!::cameraScan.isInitialized) return
        if (PermissionUtils.checkPermission(this, Manifest.permission.CAMERA)) {
            cameraScan.startCamera()
        } else {
            LogUtils.d("checkPermissionResult != PERMISSION_GRANTED")
            PermissionUtils.requestPermission(
                this,
                Manifest.permission.CAMERA,
                CAMERA_PERMISSION_REQUEST_CODE,
            )
        }
    }

    private fun releaseCamera() {
        if (::cameraScan.isInitialized) cameraScan.release()
    }

    private fun toggleTorchState() {
        if (!::cameraScan.isInitialized) return
        val next = !cameraScan.isTorchEnabled
        cameraScan.enableTorch(next)
        torchEnabled.value = next
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            requestCameraPermissionResult(permissions, grantResults)
        }
    }

    private fun requestCameraPermissionResult(permissions: Array<String>, grantResults: IntArray) {
        if (PermissionUtils.requestPermissionsResult(
                Manifest.permission.CAMERA,
                permissions,
                grantResults,
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
        if (importedN.get() > 0) {
            var text = getString(R.string.action_import_msg)
            text += "\n" + importedN.get() + " profile(s)"
            Toast.makeText(app, text, Toast.LENGTH_LONG).show()
        }
    }

    private fun BaseService.State.isSpaceActive(): Boolean {
        return this == BaseService.State.Connecting ||
            this == BaseService.State.Connected ||
            this == BaseService.State.Stopping
    }

    private companion object {
        const val CAMERA_PERMISSION_REQUEST_CODE = 0X86
    }
}
