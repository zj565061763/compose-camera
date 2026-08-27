package com.sd.demo.compose.camera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sd.demo.compose.camera.theme.AppTheme
import com.sd.lib.compose.camera.CameraMirrorMode
import com.sd.lib.compose.camera.CameraPreview
import com.sd.lib.compose.camera.rememberCameraDevicesState
import com.sd.lib.compose.camera.rememberCameraPreviewState

class SampleActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      AppTheme {
        Content()
      }
    }
  }
}

@Composable
private fun Content(
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  var hasCameraPermission by remember(context) {
    mutableStateOf(
      ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED,
    )
  }
  val permissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission(),
  ) { granted ->
    hasCameraPermission = granted
  }

  DisposableEffect(context, lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) {
        hasCameraPermission = ContextCompat.checkSelfPermission(
          context,
          Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  LaunchedEffect(Unit) {
    if (!hasCameraPermission) {
      permissionLauncher.launch(Manifest.permission.CAMERA)
    }
  }

  Box(modifier = modifier.fillMaxSize()) {
    if (hasCameraPermission) {
      CameraContent()
    } else {
      CameraPermissionContent { permissionLauncher.launch(Manifest.permission.CAMERA) }
    }
  }
}

@Composable
private fun CameraContent(
  modifier: Modifier = Modifier,
) {
  val state = rememberCameraPreviewState()
  val devicesState = rememberCameraDevicesState()

  val devices by devicesState.devices
  val devicesLoading by devicesState.isLoading
  var selectedCameraId by rememberSaveable { mutableStateOf<String?>(null) }
  var mirrorMode by rememberSaveable { mutableStateOf(CameraMirrorMode.AUTO) }

  LaunchedEffect(devices, devicesLoading) {
    if (!devicesLoading && devices.none { it.cameraId == selectedCameraId }) {
      selectedCameraId = devices.firstOrNull()?.cameraId
    }
  }
  val selectedCamera = devices.firstOrNull { it.cameraId == selectedCameraId }

  Column(
    modifier = modifier.fillMaxSize(),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(1f),
      contentAlignment = Alignment.Center,
    ) {
      if (selectedCamera != null) {
        CameraPreview(
          modifier = Modifier.fillMaxSize(),
          state = state,
          devicesState = devicesState,
          cameraId = selectedCamera.cameraId,
          mirrorMode = mirrorMode,
          onError = { error -> logMsg { "onError ${error.stackTraceToString()}" } },
        )
      } else {
        val message = when {
          devicesState.isLoading.value -> "正在读取摄像头"
          devicesState.error.value != null -> "读取摄像头失败"
          else -> "未发现可用摄像头"
        }
        Text(text = message)
      }
    }

    selectedCamera?.also { camera ->
      Text(text = "cameraId=${camera.cameraId}, lens=${camera.lens ?: "UNKNOWN"}")
    }

    Button(
      enabled = devices.size > 1,
      onClick = {
        val currentIndex = devices.indexOfFirst { it.cameraId == selectedCameraId }
        if (currentIndex >= 0) {
          selectedCameraId = devices[(currentIndex + 1) % devices.size].cameraId
        }
      },
    ) {
      Text(text = "切换")
    }

    Button(
      onClick = {
        mirrorMode = when (mirrorMode) {
          CameraMirrorMode.AUTO -> CameraMirrorMode.ON
          CameraMirrorMode.ON -> CameraMirrorMode.OFF
          CameraMirrorMode.OFF -> CameraMirrorMode.AUTO
        }
      },
    ) {
      Text(text = "镜像：$mirrorMode")
    }
  }
}

@Composable
private fun CameraPermissionContent(
  modifier: Modifier = Modifier,
  onRequestPermission: () -> Unit,
) {
  Column(
    modifier = modifier
      .fillMaxSize()
      .background(Color.Black)
      .padding(24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Text(
      text = "需要摄像头权限才能显示预览",
      color = Color.White,
      style = MaterialTheme.typography.bodyLarge,
    )
    Button(
      modifier = Modifier.padding(top = 16.dp),
      onClick = onRequestPermission,
    ) {
      Text("授予摄像头权限")
    }
  }
}
