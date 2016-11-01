package com.tomovwgti.visiblelight

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.support.annotation.NonNull
import android.support.v4.app.ActivityCompat
import android.support.v4.content.PermissionChecker
import android.util.Log
import android.util.Size
import android.view.*
import android.widget.Toast
import com.google.zxing.BinaryBitmap
import com.google.zxing.common.HybridBinarizer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.*

class MainActivity : AppCompatActivity() {

    private var mCamera: Camera? = null
    private var count: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val textureView = findViewById(R.id.texture_view) as TextureView
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                mCamera?.open()
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                return false
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
            }
        }

        // パーミッションを持っているか確認する
        if (PermissionChecker.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            // パーミッションをリクエストする
            requestCameraPermission()
        } else {
            mCamera = Camera(textureView)
        }
    }

    fun startCamera() {
        mCamera = Camera(findViewById(R.id.texture_view) as TextureView)
    }

    internal inner class Camera(private val mTextureView: TextureView) {
        private var mCamera: CameraDevice? = null
        private var mCameraSize: Size? = null

        private val mCameraDeviceCallback = object : CameraDevice.StateCallback() {
            override fun onOpened(@NonNull camera: CameraDevice) {
                mCamera = camera
                createCaptureSession()
            }

            override fun onDisconnected(@NonNull camera: CameraDevice) {
                camera.close()
                mCamera = null
                if (null != mImageReader) {
                    mImageReader?.close()
                    mImageReader = null
                }
            }

            override fun onError(@NonNull camera: CameraDevice, error: Int) {
                camera.close()
                mCamera = null
            }
        }

        var mCameraCaptureSessionCallback: CameraCaptureSession.StateCallback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(@NonNull session: CameraCaptureSession) {
                mPreviewSession = session
                updatePreview()
            }

            override fun onConfigureFailed(@NonNull session: CameraCaptureSession) {
                Toast.makeText(this@MainActivity, "onConfigureFailed", Toast.LENGTH_LONG).show()
            }
        }

        fun open() {
            try {
                val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                for (cameraId in manager.cameraIdList) {
                    val characteristics = manager.getCameraCharacteristics(cameraId)
                    if (characteristics.get(CameraCharacteristics.LENS_FACING) === CameraCharacteristics.LENS_FACING_BACK) {
                        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        mCameraSize = map.getOutputSizes(SurfaceTexture::class.java)[0]

                        mImageReader = ImageReader.newInstance(mCameraSize?.width as Int, mCameraSize?.height as Int, sImageFormat, 2)
                        mImageReader?.setOnImageAvailableListener(mOnImageAvailableListener, backgroundHandler)
                        manager.openCamera(cameraId, mCameraDeviceCallback, backgroundHandler)
                        return
                    }
                }
            } catch (e: CameraAccessException) {
                e.printStackTrace()
            }

        }

        private fun createCaptureSession() {
            if (!mTextureView.isAvailable) {
                return
            }

            val texture = mTextureView.surfaceTexture
            texture.setDefaultBufferSize(mCameraSize?.width as Int, mCameraSize?.height as Int)
            val surface = Surface(texture)
            val mImageSurface = mImageReader?.getSurface()
            try {
                mPreviewBuilder = mCamera?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            } catch (e: CameraAccessException) {
                e.printStackTrace()
            }
            mPreviewBuilder?.addTarget(mImageSurface)
            mPreviewBuilder?.addTarget(surface)
            try {
                mCamera?.createCaptureSession(Arrays.asList<Surface>(mImageSurface, surface), mCameraCaptureSessionCallback, null)
            } catch (e: CameraAccessException) {
                e.printStackTrace()
            }
        }

        private fun updatePreview() {
            mPreviewBuilder?.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_STATE_ACTIVE_SCAN)
            val thread = HandlerThread("CameraPreview")
            Log.v(TAG, "update")
            thread.start()
            backgroundHandler = Handler(thread.looper)

            try {
                mPreviewSession?.setRepeatingRequest(mPreviewBuilder?.build(), mCaptureCallback, backgroundHandler)
            } catch (e: CameraAccessException) {
                e.printStackTrace()
            }
        }
    }

    private val mCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureProgressed(session: CameraCaptureSession,
                                request: CaptureRequest,
                                partialResult: CaptureResult) {
            // キャプチャの進捗状況（随時呼び出される）
            process(partialResult)
        }

        override fun onCaptureCompleted(session: CameraCaptureSession,
                               request: CaptureRequest,
                               result: TotalCaptureResult) {
            // キャプチャの完了（プレビューの場合、プレビュー状態が継続）
            process(result)
        }

        private fun process(result: CaptureResult) {
        }
    }

    /**
     * カメラアクセス許可OK/NG
     */
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (REQUEST_CODE_CAMERA_PERMISSION === requestCode) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // カメラアクセス許可
                startCamera()
            } else {
                // アクセス拒否
                Toast.makeText(this, "カメラの使用を拒否されました", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * カメラパーミッションの取得
     */
    private fun requestCameraPermission() {
        if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)) {
            // 権限をリクエスト
            requestPermissions(arrayOf(Manifest.permission.CAMERA),
                    REQUEST_CODE_CAMERA_PERMISSION)
            return
        }
        // 権限を取得する
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA),
                REQUEST_CODE_CAMERA_PERMISSION)
    }

    private val mOnImageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        Log.v(TAG, "onImageAvailable: " + count++)
        var img: Image? = null
        img = reader.acquireLatestImage()

        if (img != null) {
            val buffer = img.planes[0].buffer
            val data = ByteArray(buffer.remaining())
            buffer.get(data)
            val width = img.width
            val height = img.height
            val source = PlanarYUVLuminanceSource(data, width, height)
            // ビットマップを取得
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            img.close()
        }
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName
        private val REQUEST_CODE_CAMERA_PERMISSION = 0x99
        private val sImageFormat = ImageFormat.YUV_420_888

        private var mPreviewBuilder: CaptureRequest.Builder? = null
        private var mPreviewSession: CameraCaptureSession? = null
        private var mImageReader: ImageReader? = null
        private var backgroundHandler: Handler? = null
    }
}
