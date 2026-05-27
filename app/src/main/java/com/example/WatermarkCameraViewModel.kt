package com.example

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

sealed interface SaveStatus {
    object Idle : SaveStatus
    object Saving : SaveStatus
    data class Success(val uri: Uri, val path: String) : SaveStatus
    data class Error(val message: String) : SaveStatus
}

class WatermarkCameraViewModel : ViewModel() {

    // Watermark Configuration State
    private val _config = MutableStateFlow(WatermarkConfig())
    val config: StateFlow<WatermarkConfig> = _config.asStateFlow()

    // Real-time ticking Date-Time
    private val _currentTime = MutableStateFlow("")
    val currentTime: StateFlow<String> = _currentTime.asStateFlow()

    // GPS Latitude and Longitude coordinates
    private val _coordinates = MutableStateFlow("正在定位中...")
    val coordinates: StateFlow<String> = _coordinates.asStateFlow()

    // Reverse-geocoded or manually overridden address
    private val _address = MutableStateFlow("正在获取地理位置...")
    val address: StateFlow<String> = _address.asStateFlow()

    // Camera hardware states (Front/Back)
    private val _lensFacing = MutableStateFlow(CameraSelector.LENS_FACING_BACK)
    val lensFacing: StateFlow<Int> = _lensFacing.asStateFlow()

    // Flash mode (OFF, ON, AUTO)
    private val _flashMode = MutableStateFlow(ImageCapture.FLASH_MODE_OFF)
    val flashMode: StateFlow<Int> = _flashMode.asStateFlow()

    // Status tracker for taken photos
    private val _saveStatus = MutableStateFlow<SaveStatus>(SaveStatus.Idle)
    val saveStatus: StateFlow<SaveStatus> = _saveStatus.asStateFlow()

    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null

    init {
        // Start live ticking clock
        viewModelScope.launch {
            while (isActive) {
                val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                _currentTime.value = format.format(Date())
                delay(1000)
            }
        }
    }

    fun updateCustomText(text: String) {
        _config.update { it.copy(customText = text) }
    }

    fun updateBadgeText(text: String) {
        _config.update { it.copy(badgeText = text) }
    }

    fun updatePosition(position: WatermarkPosition) {
        _config.update { it.copy(position = position) }
    }

    fun updateTheme(theme: WatermarkTheme) {
        _config.update { it.copy(theme = theme) }
    }

    fun updateShowDate(show: Boolean) {
        _config.update { it.copy(showDate = show) }
    }

    fun updateShowLocation(show: Boolean) {
        _config.update { it.copy(showLocation = show) }
    }

    fun updateShowCoordinates(show: Boolean) {
        _config.update { it.copy(showCoordinates = show) }
    }

    fun updateShowCustomText(show: Boolean) {
        _config.update { it.copy(showCustomText = show) }
    }

    fun overrideAddress(manualAddress: String) {
        _config.update { it.copy(customLocation = manualAddress) }
        _address.value = manualAddress
    }

    fun toggleLensFacing() {
        _lensFacing.update { facing ->
            if (facing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }
        }
    }

    fun cycleFlashMode() {
        _flashMode.update { current ->
            when (current) {
                ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
                ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_AUTO
                else -> ImageCapture.FLASH_MODE_OFF
            }
        }
    }

    fun resetSaveStatus() {
        _saveStatus.value = SaveStatus.Idle
    }

    /**
     * Set up location listener using Google Play Services.
     */
    @SuppressLint("MissingPermission")
    fun startLocationUpdates(context: Context) {
        try {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    context, 
                    android.Manifest.permission.ACCESS_FINE_LOCATION
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return
            }
            
            if (fusedLocationClient == null) {
                fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
            }
            
            // Remove previous callback if exists to avoid leak
            stopLocationUpdates()

            // Try to get last location immediately for faster display
            fusedLocationClient?.lastLocation?.addOnSuccessListener { loc ->
                try {
                    if (loc != null) {
                        processLocation(context, loc.latitude, loc.longitude)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }?.addOnFailureListener { e ->
                e.printStackTrace()
            }

            // Create high priority GPS updates or balanced updates based on permissions
            val hasFineLocation = androidx.core.content.ContextCompat.checkSelfPermission(
                context, 
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            
            val priority = if (hasFineLocation) {
                Priority.PRIORITY_HIGH_ACCURACY
            } else {
                Priority.PRIORITY_BALANCED_POWER_ACCURACY
            }

            val locationRequest = LocationRequest.Builder(
                priority,
                10000L // 10s intervals
            ).apply {
                setMinUpdateIntervalMillis(5000L)
                if (hasFineLocation) {
                    setGranularity(Granularity.GRANULARITY_FINE)
                } else {
                    setGranularity(Granularity.GRANULARITY_COARSE)
                }
            }.build()

            locationCallback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    try {
                        val lastLoc = result.lastLocation
                        if (lastLoc != null) {
                            processLocation(context, lastLoc.latitude, lastLoc.longitude)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            fusedLocationClient?.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                context.mainLooper
            )
        } catch (e: Exception) {
            e.printStackTrace()
            _coordinates.value = "GPS定位不可用"
            _address.value = "无法自动定位，支持手动输入"
        }
    }

    fun stopLocationUpdates() {
        try {
            val client = fusedLocationClient
            val cb = locationCallback
            if (client != null && cb != null) {
                client.removeLocationUpdates(cb)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun processLocation(context: Context, lat: Double, lon: Double) {
        val latDirection = if (lat >= 0) "N" else "S"
        val lonDirection = if (lon >= 0) "E" else "W"
        
        // Pretty formatting for coordinates
        val coordStr = String.format(
            Locale.US,
            "%.5f°%s, %.5f°%s",
            Math.abs(lat), latDirection,
            Math.abs(lon), lonDirection
        )
        _coordinates.value = coordStr

        // Handle Reverse Geocoding in IO thread
        if (_config.value.customLocation.isEmpty()) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    if (Geocoder.isPresent()) {
                        val geocoder = Geocoder(context, Locale.getDefault())
                        @Suppress("DEPRECATION")
                        val addresses = geocoder.getFromLocation(lat, lon, 1)
                        if (!addresses.isNullOrEmpty()) {
                            val addr = addresses[0]
                            val fullAddress = addr.getAddressLine(0) ?: ""
                            val cleanAddress = when {
                                fullAddress.isNotEmpty() -> fullAddress
                                else -> {
                                    val prov = addr.adminArea ?: ""
                                    val city = addr.locality ?: ""
                                    val dist = addr.subLocality ?: ""
                                    val street = addr.thoroughfare ?: ""
                                    "$prov$city$dist$street"
                                }
                            }
                            withContext(Dispatchers.Main) {
                                if (_config.value.customLocation.isEmpty()) {
                                    _address.value = cleanAddress
                                }
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                if (_config.value.customLocation.isEmpty()) {
                                    _address.value = "$coordStr (无法获取地名)"
                                }
                            }
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            if (_config.value.customLocation.isEmpty()) {
                                _address.value = "$coordStr (不适用地理编码)"
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        if (_config.value.customLocation.isEmpty()) {
                            _address.value = "网络未连接，已锁定GPS坐标"
                        }
                    }
                }
            }
        }
    }

    /**
     * Capture raw Frame, convert, overlay watermark, and save to Android system Gallery files.
     */
    fun processAndSaveCapturedImage(context: Context, imageProxy: ImageProxy) {
        _saveStatus.value = SaveStatus.Saving
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Determine orientation degree
                val rotationDegrees = imageProxy.imageInfo.rotationDegrees.toFloat()

                // Extract bitmap from ImageProxy buffer
                val buffer = imageProxy.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                val rawBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                
                // Release the ImageProxy instantly
                imageProxy.close()

                if (rawBitmap == null) {
                    withContext(Dispatchers.Main) {
                        _saveStatus.value = SaveStatus.Error("读取相机数据失败")
                    }
                    return@launch
                }

                // 1. Correct physical rotation
                val rotatedBitmap = ImageWatermarkProcessor.rotateBitmap(rawBitmap, rotationDegrees)

                // 2. Overlay custom watermarking with current snapshots
                val configSnap = _config.value
                val dateSnap = _currentTime.value
                val coordSnap = _coordinates.value
                val addrSnap = _address.value

                val finalBitmap = ImageWatermarkProcessor.applyWatermark(
                    bitmap = rotatedBitmap,
                    config = configSnap,
                    dateTime = dateSnap,
                    coordinates = coordSnap,
                    address = addrSnap
                )

                // 3. Write into Public Gallery folder
                val savedUri = ImageWatermarkProcessor.saveToGallery(context, finalBitmap)

                // 4. Recycle intermediate bitmaps to optimize memory usage
                if (rawBitmap != rotatedBitmap) rawBitmap.recycle()
                rotatedBitmap.recycle()

                withContext(Dispatchers.Main) {
                    if (savedUri != null) {
                        _saveStatus.value = SaveStatus.Success(
                            uri = savedUri,
                            path = "Pictures/WatermarkCamera"
                        )
                    } else {
                        _saveStatus.value = SaveStatus.Error("无法保存图片到相册")
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    _saveStatus.value = SaveStatus.Error(e.localizedMessage ?: "未知错误")
                }
            } finally {
                try { imageProxy.close() } catch (ignored: Exception) {}
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopLocationUpdates()
    }
}
