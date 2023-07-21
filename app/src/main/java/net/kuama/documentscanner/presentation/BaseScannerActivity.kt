package net.kuama.documentscanner.presentation

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentResolver
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.yuyakaido.android.cardstackview.CardStackLayoutManager
import com.yuyakaido.android.cardstackview.StackFrom
import com.yuyakaido.android.cardstackview.SwipeableMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.kuama.documentscanner.R
import net.kuama.documentscanner.data.OpenCVLoader
import net.kuama.documentscanner.databinding.ActivityScannerBinding
import net.kuama.documentscanner.enums.EFlashStatus
import net.kuama.documentscanner.extensions.deleteIfLocal
import net.kuama.documentscanner.extensions.hide
import net.kuama.documentscanner.extensions.logError
import net.kuama.documentscanner.extensions.outputDirectory
import net.kuama.documentscanner.extensions.show
import net.kuama.documentscanner.viewmodels.ScannerViewModel
import java.io.File
import java.io.FileOutputStream

abstract class BaseScannerActivity : AppCompatActivity() {
    companion object {
        private val TAG = ScannerActivity::class.java.simpleName
    }

    lateinit var viewModel: ScannerViewModel
    private lateinit var binding: ActivityScannerBinding
    private val takenPhotosAdapter = StackViewAdapter()

    private val resultLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val bitmapUri =
                    result.data?.extras?.getString("croppedPath") ?: error("invalid path")

                val uri = Uri.fromFile(File(bitmapUri))
                viewModel.savePhoto(uri)
                //todo: delete the original image file when it's not needed anymore
            } else {
                logError(TAG, "resultLauncher: $result.resultCode")
                viewModel.onViewCreated(OpenCVLoader(this), this, binding.viewFinder)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityScannerBinding.inflate(layoutInflater)

        setContentView(binding.root)

        val viewModel: ScannerViewModel by viewModels()

        viewModel.isBusy.observe(this) { isBusy ->
            binding.progress.visibility = if (isBusy) {
                View.VISIBLE
            } else {
                View.INVISIBLE
            }
        }

        viewModel.lastUri.observe(this) {
            val intent = Intent(this, CropperActivity::class.java)
            intent.putExtra("lastUri", it.toString())
            intent.putExtra("screenOrientationDeg", viewModel.screenOrientationDeg.value)

            resultLauncher.launch(intent)
        }

        viewModel.errors.observe(this) {
            onError(it)
            logError(TAG, it.message)
        }

        viewModel.corners.observe(this) {
            it?.let { corners ->
                binding.hud.onCornersDetected(corners)
            } ?: run {
                binding.hud.onCornersNotDetected()
            }
        }

        viewModel.flashStatus.observe(this) { status ->
            binding.flashMode.setImageResource(
                when (status) {
                    EFlashStatus.ON -> R.drawable.flash_on
                    EFlashStatus.OFF -> R.drawable.flash_off
                    else -> R.drawable.flash_off
                }
            )
        }

        binding.flashMode.setOnClickListener {
            viewModel.onFlashToggle()
        }

        binding.takePicture.setOnClickListener {
            viewModel.onTakePicture(this.outputDirectory(), this)
        }

        binding.done.setOnClickListener {
            onDoneClicked()
        }

        binding.closeScanner.setOnClickListener {
            closePreview()
        }
        setUpPreviewAdapter()
//        setOnPreviewStackClicked()

        this.viewModel = viewModel
        orientationEventListener.enable()
    }

    override fun onResume() {
        super.onResume()
        orientationEventListener.enable()
        viewModel.onViewCreated(OpenCVLoader(this), this, binding.viewFinder)
        updateUiElements()
//        setOnPreviewStackClicked()
//        updateDialog()
    }


    private fun closePreview() {
        binding.rootView.visibility = View.GONE
        viewModel.onClosePreview()
        orientationEventListener.disable()
        finish()
    }

    private fun onDoneClicked() {
        viewModel.takenPhotos.observe(this) { photos ->
            lifecycleScope.launch {
                val bitmapsList = mutableListOf<Bitmap>()
                photos.forEach { uri ->
                    val bitmap =
                        getBitmapFromImageUri(uri) ?: return@forEach
                    bitmapsList.add(bitmap)
                    uri.deleteIfLocal()
                }
                outputDirectory().delete()
                // for normal ordering of the pages, otherwise the pages are reversed
                bitmapsList.reverse()
                withContext(Dispatchers.IO) {
                    convertBitmapsToPdf(bitmapsList)
                }
                // todo: pass the PDF document
                setResult(RESULT_OK)
                finish()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun getBitmapFromImageUri(uri: Uri): Bitmap? {
        return try {
            val contentResolver: ContentResolver = this.contentResolver
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.decodeBitmap(
                    ImageDecoder.createSource(contentResolver, uri)
                ) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = true
                }
            } else {
                MediaStore.Images.Media.getBitmap(contentResolver, uri)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun convertBitmapsToPdf(bitmaps: List<Bitmap>) {
        // Works for the emulator
        //        val outputPath =
        //            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath + "/output.pdf"
        // Use this path to save  the PDF document to, when using as a library
        val outputPath = applicationContext.cacheDir.absolutePath + "/output.pdf"
        val document = PdfDocument()
        for ((index, bitmap) in bitmaps.withIndex()) {
            val pageNumber = index + 1
            val pageInfo =
                PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, pageNumber).create()
            val page = document.startPage(pageInfo)
            val canvas: Canvas = page.canvas

            canvas.drawBitmap(bitmap, 0f, 0f, null)
            document.finishPage(page)
        }
        // todo: delete the file when it's not needed anymore
        val file = File(outputPath)
        if (file.exists()) {
            val isDeleted = file.delete()
            if (!isDeleted) {
                logError(TAG, "convertBitmapsToPdf: The file was not deleted")
            }
        }
        try {
            FileOutputStream(file).use { fileOutputStream ->
                document.writeTo(fileOutputStream)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            document.close()
        }
    }

    private fun setUpPreviewAdapter() {
        val layoutManager = CardStackLayoutManager(this).apply {
            setStackFrom(StackFrom.TopAndRight)
            setSwipeableMethod(SwipeableMethod.None)
            setVisibleCount(3)
        }
        binding.previewStack.layoutManager = layoutManager
        binding.previewStack.adapter = takenPhotosAdapter
    }

    private fun updateDialog() {
        val dialogFragment =
            this.supportFragmentManager.findFragmentByTag(ReviewTakenPhotosDialog::class.simpleName) as? DialogFragment
        if (dialogFragment?.dialog != null) {
            dialogFragment.dismiss()
            ReviewTakenPhotosDialog.show(this, takenPhotosAdapter.imageUris) { removedItemIndex ->
                viewModel.deletePhoto(removedItemIndex)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setOnPreviewStackClicked() {
        binding.previewStack.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                ReviewTakenPhotosDialog.show(this, takenPhotosAdapter.imageUris) { removedItemIndex ->
                    viewModel.deletePhoto(removedItemIndex)
                }
                return@setOnTouchListener true
            }
            return@setOnTouchListener true
        }
    }

    private fun updateUiElements() {
        viewModel.takenPhotos.observe(this) { photos ->
            takenPhotosAdapter.addImageUris(*photos.toTypedArray())
            if (photos.isNullOrEmpty()) {
                binding.apply {
                    cameraElementsWrapper.setBackgroundColor(Color.TRANSPARENT)
                    takePicture.show()
                    done.hide()
                    previewStack.hide()
                }
            } else {
                binding.apply {
                    cameraElementsWrapper.setBackgroundColor(
                        this@BaseScannerActivity.getColor(
                            R.color.darkGray
                        )
                    )
                    done.show()
                    previewStack.show()
                }
            }
        }
    }

    private val orientationEventListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                val rotationDegree = when (orientation) {
                    ORIENTATION_UNKNOWN -> return
                    in 45 until 135 -> 270
                    in 135 until 225 -> 180
                    in 225 until 315 -> 90
                    else -> Surface.ROTATION_0
                }

                viewModel.onScreenOrientationDegChange(rotationDegree)
            }
        }
    }

    abstract fun onError(throwable: Throwable)

    //    abstract fun onDocumentAccepted(bitmap: Bitmap, urisList: List<Uri>? = null)
    abstract fun onClose()
}
