package com.sd.lib.compose.camera

import android.content.pm.FeatureInfo
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraManifestTest {
  @Test
  fun cameraFeatures_areOptional() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val packageInfo = context.packageManager.getPackageInfo(
      context.packageName,
      PackageManager.GET_CONFIGURATIONS,
    )
    val featuresByName = packageInfo.reqFeatures.orEmpty().associateBy(FeatureInfo::name)

    assertThat(featuresByName[PackageManager.FEATURE_CAMERA]).isNotNull()
    assertThat(featuresByName[PackageManager.FEATURE_CAMERA]!!.flags and FeatureInfo.FLAG_REQUIRED)
      .isEqualTo(0)
    assertThat(featuresByName[PackageManager.FEATURE_CAMERA_AUTOFOCUS]).isNotNull()
    assertThat(
      featuresByName[PackageManager.FEATURE_CAMERA_AUTOFOCUS]!!.flags and
        FeatureInfo.FLAG_REQUIRED,
    ).isEqualTo(0)
  }
}
