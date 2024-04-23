package com.ars.wakeup

import android.Manifest
import android.app.AlertDialog
import android.content.ContentValues
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.room.Room
import com.ars.wakeup.data.WakeUpAdapter
import com.ars.wakeup.database.AppDatabase
import com.ars.wakeup.database.WakeUpHistory
import com.ars.wakeup.databinding.ActivityMainBinding
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var viewBinding: ActivityMainBinding
    private lateinit var db: AppDatabase
    private lateinit var wakeUpList: ArrayList<WakeUpHistory>
    private lateinit var wakeUpAdapter: WakeUpAdapter
    private var ringtone: Ringtone? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions()
        }

        // Set up the listener for video capture button
        viewBinding.btVideoStart.setOnClickListener { captureVideo() }

        viewBinding.btHistoric.setOnClickListener {
            showHistoricModal()
        }

        wakeUpList = ArrayList()
        cameraExecutor = Executors.newSingleThreadExecutor()

        initDatabase()

        viewBinding.btAlarm1.setOnClickListener {
            toggleAlarm(viewBinding.btAlarm1, RingtoneManager.TYPE_NOTIFICATION)
        }

        viewBinding.btAlarm2.setOnClickListener {
            toggleAlarm(viewBinding.btAlarm2, RingtoneManager.TYPE_ALARM)
        }
    }

    private fun captureVideo() {
        val videoCapture = this.videoCapture ?: return

        viewBinding.btVideoStart.isEnabled = false
        viewBinding.btHistoric.isActivated = false

        val curRecording = recording
        if (curRecording != null) {
            // Stop the current recording session.
            curRecording.stop()
            recording = null
            return
        }

        // create and start a new recording session
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraX-Video")
            }
        }

        val mediaStoreOutputOptions = MediaStoreOutputOptions
            .Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()
        recording = videoCapture.output
            .prepareRecording(this, mediaStoreOutputOptions)
            .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                when(recordEvent) {
                    is VideoRecordEvent.Start -> {

                        viewBinding.btHistoric.apply {
                            isVisible = false
                        }
                        viewBinding.btAlarm1.apply {
                            isVisible = true
                        }
                        viewBinding.btAlarm2.apply {
                            isVisible = true
                        }

                        viewBinding.btVideoStart.apply {
                            contentDescription = getString(R.string.stop_capture)
                            backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.green))
                            layoutParams.width = resources.getDimensionPixelSize(R.dimen.button_100)
                            layoutParams.height = resources.getDimensionPixelSize(R.dimen.button_100)
                            isEnabled = true
                        }
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (!recordEvent.hasError()) {
                            val msg = "Video capture succeeded: " +
                                    "${recordEvent.outputResults.outputUri}"
                            Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT)
                                .show()
                            Log.d(TAG, msg)
                        } else {
                            recording?.close()
                            recording = null
                            Log.e(TAG, "Video capture ends with error: " +
                                    "${recordEvent.error}")
                        }

                        viewBinding.btHistoric.apply {
                            isVisible = true
                        }
                        viewBinding.btAlarm1.apply {
                            isVisible = false
                        }
                        viewBinding.btAlarm2.apply {
                            isVisible = false
                        }

                        viewBinding.btVideoStart.apply {
                            contentDescription = getString(R.string.start_capture)
                            backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.red))
                            layoutParams.width = resources.getDimensionPixelSize(R.dimen.button_150)
                            layoutParams.height = resources.getDimensionPixelSize(R.dimen.button_150)
                            isEnabled = true
                        }

                        val qtd = if (wakeUpList.isNotEmpty()) wakeUpList.count() + 1 else 1
                        // used to generate random values
                        val wakeUpHistory = WakeUpHistory(id = qtd,
                            dateStart = Date(System.currentTimeMillis()),
                            dateEnd = Date(System.currentTimeMillis() + 60 * 60 * 1000),
                            travelMinutes = kotlin.random.Random.nextInt(30, 1000),
                            travelOccurrences = kotlin.random.Random.nextInt(1, 10))

                        saveData(wakeUpHistory)
                    }
                }
            }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewBinding.pvFinder.surfaceProvider)
                }

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, videoCapture)

            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun requestPermissions() {
        activityResultLauncher.launch(REQUIRED_PERMISSIONS)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CameraXApp"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private val REQUIRED_PERMISSIONS =
            mutableListOf (
                Manifest.permission.CAMERA
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }

    private val activityResultLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions())
        { permissions ->
            // Handle Permission granted/rejected
            var permissionGranted = true
            permissions.entries.forEach {
                if (it.key in REQUIRED_PERMISSIONS && !it.value)
                    permissionGranted = false
            }
            if (!permissionGranted) {
                Toast.makeText(baseContext,
                    "Permission request denied",
                    Toast.LENGTH_SHORT).show()
            } else {
                startCamera()
            }
        }

    private fun showHistoricModal() {
        val dialogView = layoutInflater.inflate(R.layout.historic_layout, null)

        val rv = dialogView.findViewById<RecyclerView>(R.id.rv_history)
        setupRecyclerView(rv)

        val builder = AlertDialog.Builder(this)
        builder.setView(dialogView)
        val alertDialog = builder.create()
        alertDialog.show()

        loadData()
    }

    private fun toggleAlarm(button: FloatingActionButton, soundResource: Int) {
        if (button.isActivated) {
            stopAlarm()
        } else {
            startAlarm(soundResource)
        }

        button.isActivated = !button.isActivated
    }

    private fun startAlarm(soundResource: Int) {
        val alarmSound = Uri.parse("android.resource://$packageName/$soundResource")
        ringtone = RingtoneManager.getRingtone(applicationContext, alarmSound)
        ringtone?.play()
    }

    private fun stopAlarm() {
        ringtone?.stop()
    }

    private fun initDatabase() {
        db = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java, "database-history"
        )
            .allowMainThreadQueries()
            .build()
    }

    private fun setupRecyclerView(rv: RecyclerView) {
        rv.layoutManager = LinearLayoutManager(this)

        wakeUpList = ArrayList()
        wakeUpAdapter = WakeUpAdapter(wakeUpList, 1)
        rv.adapter = wakeUpAdapter
    }

    private fun loadData() {
        wakeUpList.clear()
        wakeUpList.addAll(db.wakeUpDao().getAll() as ArrayList<WakeUpHistory>)
    }

    private fun saveData(value: WakeUpHistory) {
        db.wakeUpDao().insertAll(value)
    }
}