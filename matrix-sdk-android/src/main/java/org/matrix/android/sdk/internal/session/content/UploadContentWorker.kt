/*
 * Optimized UploadContentWorker.kt with coroutine enhancements, thumbnail parallelism,
 * and buffered IO improvements for better upload performance.
 */

package org.matrix.android.sdk.internal.session.content

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.core.net.toUri
import androidx.work.WorkerParameters
import com.squareup.moshi.JsonClass
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers.IO
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.listeners.ProgressListener
import org.matrix.android.sdk.api.session.content.ContentAttachmentData
import org.matrix.android.sdk.api.session.crypto.model.EncryptedFileInfo
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.events.model.toContent
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.message.*
import org.matrix.android.sdk.api.util.MimeTypes
import org.matrix.android.sdk.internal.SessionManager
import org.matrix.android.sdk.internal.crypto.attachments.MXEncryptedAttachments
import org.matrix.android.sdk.internal.database.mapper.ContentMapper
import org.matrix.android.sdk.internal.database.mapper.asDomain
import org.matrix.android.sdk.internal.network.ProgressRequestBody
import org.matrix.android.sdk.internal.session.DefaultFileService
import org.matrix.android.sdk.internal.session.SessionComponent
import org.matrix.android.sdk.internal.session.room.send.*
import org.matrix.android.sdk.internal.util.TemporaryFileCreator
import org.matrix.android.sdk.internal.util.time.Clock
import org.matrix.android.sdk.internal.util.toMatrixErrorStr
import org.matrix.android.sdk.internal.worker.SessionSafeCoroutineWorker
import org.matrix.android.sdk.internal.worker.SessionWorkerParams
import org.matrix.android.sdk.internal.worker.WorkerParamsFactory
import timber.log.Timber
import java.io.*
import javax.inject.Inject

private data class NewAttachmentAttributes(
        val newWidth: Int? = null,
        val newHeight: Int? = null,
        val newFileSize: Long
)

/**
 * Possible previous worker: None.
 * Possible next worker    : Always [MultipleEventSendingDispatcherWorker].
 */
internal class UploadContentWorker(val context: Context, params: WorkerParameters, sessionManager: SessionManager) :
        SessionSafeCoroutineWorker<UploadContentWorker.Params>(context, params, sessionManager, Params::class.java) {

    @JsonClass(generateAdapter = true)
    internal data class Params(
            override val sessionId: String,
            val localEchoIds: List<LocalEchoIdentifiers>,
            val attachment: ContentAttachmentData,
            val isEncrypted: Boolean,
            val compressBeforeSending: Boolean,
            override val lastFailureMessage: String? = null
    ) : SessionWorkerParams

    @Inject lateinit var fileUploader: FileUploader
    @Inject lateinit var contentUploadStateTracker: DefaultContentUploadStateTracker
    @Inject lateinit var fileService: DefaultFileService
    @Inject lateinit var cancelSendTracker: CancelSendTracker
    @Inject lateinit var imageCompressor: ImageCompressor
    @Inject lateinit var imageExitTagRemover: ImageExifTagRemover
    @Inject lateinit var videoCompressor: VideoCompressor
    @Inject lateinit var thumbnailExtractor: ThumbnailExtractor
    @Inject lateinit var localEchoRepository: LocalEchoRepository
    @Inject lateinit var temporaryFileCreator: TemporaryFileCreator
    @Inject lateinit var clock: Clock

    override fun injectWith(injector: SessionComponent) {
        injector.inject(this)
    }

    override suspend fun doSafeWork(params: Params): Result = try {
        coroutineScope {
            internalDoWork(params)
        }
    } catch (failure: Throwable) {
        Timber.e(failure)
        handleFailure(params, failure)
    }

    override fun buildErrorParams(params: Params, message: String): Params {
        return params.copy(lastFailureMessage = params.lastFailureMessage ?: message)
    }

    private suspend fun internalDoWork(params: Params): Result = withContext(IO) {
        val allCancelled = params.localEchoIds.all {
            cancelSendTracker.isCancelRequestedFor(it.eventId, it.roomId)
        }
        if (allCancelled) {
            // Re-add revoke permission here (early cancel path)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.revokeUriPermission(context.packageName, params.attachment.queryUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } else {
                    context.revokeUriPermission(params.attachment.queryUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } catch (e: SecurityException) {
                Timber.w(e, "Failed to revoke permission on cancel")
            }
            return@withContext Result.success(inputData)
        }

        val attachment = params.attachment
        val filesToDelete = hashSetOf<File>()

        try {
            val inputStream = context.contentResolver.openInputStream(attachment.queryUri)
                ?: return@withContext Result.success(
                    WorkerParamsFactory.toData(
                        params.copy(lastFailureMessage = "Cannot openInputStream for file: ${attachment.queryUri}")
                    )
                )

            val workingFile = temporaryFileCreator.create().also { filesToDelete.add(it) }

            BufferedOutputStream(workingFile.outputStream()).use { output ->
                BufferedInputStream(inputStream).use { input ->
                    input.copyTo(output)
                }
            }

            val progressListener = object : ProgressRequestBody.Listener {
                override fun onProgress(current: Long, total: Long) {
                    notifyTracker(params) {
                        if (isStopped) {
                            contentUploadStateTracker.setFailure(it, Throwable("Cancelled"))
                        } else {
                            contentUploadStateTracker.setProgress(it, current, total)
                        }
                    }
                }
            }

            var uploadedFileEncryptedFileInfo: EncryptedFileInfo? = null
            val fileToUpload: File
            var newAttachmentAttributes = NewAttachmentAttributes(
                attachment.width?.toInt(),
                attachment.height?.toInt(),
                attachment.size
            )

            fileToUpload = when {
                attachment.type == ContentAttachmentData.Type.IMAGE &&
                        attachment.mimeType != MimeTypes.Gif &&
                        params.compressBeforeSending -> {
                    notifyTracker(params) { contentUploadStateTracker.setCompressingImage(it) }
                    imageCompressor.compress(workingFile, MAX_IMAGE_SIZE, MAX_IMAGE_SIZE).also { compressedFile ->
                        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(compressedFile.absolutePath, options)
                        newAttachmentAttributes = newAttachmentAttributes.copy(
                            newWidth = options.outWidth,
                            newHeight = options.outHeight,
                            newFileSize = compressedFile.length()
                        )
                        filesToDelete.add(compressedFile)
                    }
                }
               attachment.type == ContentAttachmentData.Type.VIDEO &&
                        attachment.mimeType != MimeTypes.Gif &&
                        params.compressBeforeSending -> {
                    val result = videoCompressor.compress(workingFile, object : ProgressListener {
                        override fun onProgress(progress: Int, total: Int) {
                            notifyTracker(params) { contentUploadStateTracker.setCompressingVideo(it, progress.toFloat()) }
                        }
                    })
                    when (result) {
                        is VideoCompressionResult.Success -> {
                            val compressedFile = result.compressedFile
                            tryOrNull {
                                context.contentResolver.openFileDescriptor(compressedFile.toUri(), "r")?.use { pfd ->
                                    MediaMetadataRetriever().apply {
                                        setDataSource(pfd.fileDescriptor)
                                        val width = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt()
                                        val height = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt()
                                        newAttachmentAttributes = newAttachmentAttributes.copy(
                                            newWidth = width ?: newAttachmentAttributes.newWidth,
                                            newHeight = height ?: newAttachmentAttributes.newHeight,
                                            newFileSize = compressedFile.length()
                                        )
                                    }
                                }
                            }
                            filesToDelete.add(compressedFile)
                            compressedFile
                        }
                        else -> workingFile
                    }
                }
                attachment.type == ContentAttachmentData.Type.VIDEO &&

                attachment.type == ContentAttachmentData.Type.IMAGE && !params.compressBeforeSending -> {
                    imageExitTagRemover.removeSensitiveJpegExifTags(workingFile).also {
                        filesToDelete.add(it)
                        newAttachmentAttributes = newAttachmentAttributes.copy(newFileSize = it.length())
                    }
                }
                else -> {
                    if (attachment.size <= 0) {
                        newAttachmentAttributes = newAttachmentAttributes.copy(newFileSize = workingFile.length())
                    }
                    workingFile
                }
            }

            val encryptedFile: File?
            val contentUploadResponse = if (params.isEncrypted) {
                Timber.v("## Encrypt file")
                encryptedFile = temporaryFileCreator.create().also { filesToDelete.add(it) }

                uploadedFileEncryptedFileInfo = MXEncryptedAttachments.encrypt(
                    fileToUpload.inputStream(), encryptedFile, clock
                ) { read, total ->
                    notifyTracker(params) {
                        contentUploadStateTracker.setEncrypting(it, read.toLong(), total.toLong())
                    }
                }

                fileUploader.uploadFile(
                    file = encryptedFile,
                    filename = null,
                    mimeType = MimeTypes.OctetStream,
                    progressListener = progressListener
                )
            } else {
                encryptedFile = null
                fileUploader.uploadFile(
                    file = fileToUpload,
                    filename = attachment.name,
                    mimeType = attachment.getSafeMimeType(),
                    progressListener = progressListener
                )
            }

            try {
                fileService.storeDataFor(
                    mxcUrl = contentUploadResponse.contentUri,
                    filename = attachment.name,
                    mimeType = attachment.getSafeMimeType(),
                    originalFile = workingFile,
                    encryptedFile = encryptedFile
                )
            } catch (e: Throwable) {
                Timber.e(e, "## Failed to update file cache")
            }

            if (attachment.type == ContentAttachmentData.Type.VOICE_MESSAGE) {
                context.contentResolver.delete(attachment.queryUri, null, null)
            }

            val thumbnailDeferred = async(IO) { dealWithThumbnail(params) }
            val thumbnailResult = thumbnailDeferred.await()

            handleSuccess(
                params = params,
                attachmentUrl = contentUploadResponse.contentUri,
                encryptedFileInfo = uploadedFileEncryptedFileInfo,
                thumbnailUrl = thumbnailResult?.uploadedThumbnailUrl,
                thumbnailEncryptedFileInfo = thumbnailResult?.uploadedThumbnailEncryptedFileInfo,
                newAttachmentAttributes = newAttachmentAttributes
            )
        } catch (e: Exception) {
            Timber.e(e, "## ERROR")
            handleFailure(params, e)
        } finally {
            filesToDelete.forEach { tryOrNull { it.delete() } }
        }
    }

    private data class UploadThumbnailResult(
            val uploadedThumbnailUrl: String,
            val uploadedThumbnailEncryptedFileInfo: EncryptedFileInfo?
    )

    /**
     * If appropriate, it will create and upload a thumbnail.
     */
    private suspend fun dealWithThumbnail(params: Params): UploadThumbnailResult? {
        return thumbnailExtractor.extractThumbnail(params.attachment)
                ?.let { thumbnailData ->
                    val thumbnailProgressListener = object : ProgressRequestBody.Listener {
                        override fun onProgress(current: Long, total: Long) {
                            notifyTracker(params) { contentUploadStateTracker.setProgressThumbnail(it, current, total) }
                        }
                    }

                    try {
                        if (params.isEncrypted) {
                            Timber.v("Encrypt thumbnail")
                            notifyTracker(params) { contentUploadStateTracker.setEncryptingThumbnail(it) }
                            val encryptionResult = MXEncryptedAttachments.encryptAttachment(thumbnailData.bytes.inputStream(), clock)
                            val contentUploadResponse = fileUploader.uploadByteArray(
                                    byteArray = encryptionResult.encryptedByteArray,
                                    filename = null,
                                    mimeType = MimeTypes.OctetStream,
                                    progressListener = thumbnailProgressListener
                            )
                            UploadThumbnailResult(
                                    contentUploadResponse.contentUri,
                                    encryptionResult.encryptedFileInfo
                            )
                        } else {
                            val contentUploadResponse = fileUploader.uploadByteArray(
                                    byteArray = thumbnailData.bytes,
                                    filename = "thumb_${params.attachment.name}",
                                    mimeType = thumbnailData.mimeType,
                                    progressListener = thumbnailProgressListener
                            )
                            UploadThumbnailResult(
                                    contentUploadResponse.contentUri,
                                    null
                            )
                        }
                    } catch (t: Throwable) {
                        Timber.e(t, "Thumbnail upload failed")
                        null
                    }
                }
    }

    private fun handleFailure(params: Params, failure: Throwable): Result {
        notifyTracker(params) { contentUploadStateTracker.setFailure(it, failure) }

        return Result.success(
                WorkerParamsFactory.toData(
                        params.copy(
                                lastFailureMessage = failure.toMatrixErrorStr()
                        )
                )
        )
    }

    private suspend fun handleSuccess(
            params: Params,
            attachmentUrl: String,
            encryptedFileInfo: EncryptedFileInfo?,
            thumbnailUrl: String?,
            thumbnailEncryptedFileInfo: EncryptedFileInfo?,
            newAttachmentAttributes: NewAttachmentAttributes
    ): Result {
        notifyTracker(params) { contentUploadStateTracker.setSuccess(it) }
        params.localEchoIds.forEach {
            updateEvent(it.eventId, attachmentUrl, encryptedFileInfo, thumbnailUrl, thumbnailEncryptedFileInfo, newAttachmentAttributes)
        }

        val sendParams = MultipleEventSendingDispatcherWorker.Params(
                sessionId = params.sessionId,
                localEchoIds = params.localEchoIds,
                isEncrypted = params.isEncrypted
        )
        return Result.success(WorkerParamsFactory.toData(sendParams)).also {
            Timber.v("## handleSuccess $attachmentUrl, work is stopped $isStopped")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.revokeUriPermission(context.packageName, params.attachment.queryUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                context.revokeUriPermission(params.attachment.queryUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    private suspend fun updateEvent(
            eventId: String,
            url: String,
            encryptedFileInfo: EncryptedFileInfo?,
            thumbnailUrl: String? = null,
            thumbnailEncryptedFileInfo: EncryptedFileInfo?,
            newAttachmentAttributes: NewAttachmentAttributes
    ) {
        localEchoRepository.updateEcho(eventId) { _, event ->
            val content: Content? = event.asDomain(castJsonNumbers = true).content
            val messageContent: MessageContent? = content.toModel()
            // Retrieve potential additional content from the original event
            val additionalContent = content.orEmpty() - messageContent?.toContent().orEmpty().keys
            val updatedContent = when (messageContent) {
                is MessageImageContent -> messageContent.update(url, encryptedFileInfo, newAttachmentAttributes)
                is MessageVideoContent -> messageContent.update(url, encryptedFileInfo, thumbnailUrl, thumbnailEncryptedFileInfo, newAttachmentAttributes)
                is MessageFileContent -> messageContent.update(url, encryptedFileInfo, newAttachmentAttributes.newFileSize)
                is MessageAudioContent -> messageContent.update(url, encryptedFileInfo, newAttachmentAttributes.newFileSize)
                else -> messageContent
            }
            event.content = ContentMapper.map(updatedContent.toContent().plus(additionalContent))
        }
    }

    private fun notifyTracker(params: Params, function: (String) -> Unit) {
        params.localEchoIds.forEach { function.invoke(it.eventId) }
    }

    private fun MessageImageContent.update(
            url: String,
            encryptedFileInfo: EncryptedFileInfo?,
            newAttachmentAttributes: NewAttachmentAttributes?
    ): MessageImageContent {
        return copy(
                url = if (encryptedFileInfo == null) url else null,
                encryptedFileInfo = encryptedFileInfo?.copy(url = url),
                info = info?.copy(
                        width = newAttachmentAttributes?.newWidth ?: info.width,
                        height = newAttachmentAttributes?.newHeight ?: info.height,
                        size = newAttachmentAttributes?.newFileSize ?: info.size
                )
        )
    }

    private fun MessageVideoContent.update(
            url: String,
            encryptedFileInfo: EncryptedFileInfo?,
            thumbnailUrl: String?,
            thumbnailEncryptedFileInfo: EncryptedFileInfo?,
            newAttachmentAttributes: NewAttachmentAttributes?
    ): MessageVideoContent {
        return copy(
                url = if (encryptedFileInfo == null) url else null,
                encryptedFileInfo = encryptedFileInfo?.copy(url = url),
                videoInfo = videoInfo?.copy(
                        thumbnailUrl = if (thumbnailEncryptedFileInfo == null) thumbnailUrl else null,
                        thumbnailFile = thumbnailEncryptedFileInfo?.copy(url = thumbnailUrl),
                        width = newAttachmentAttributes?.newWidth ?: videoInfo.width,
                        height = newAttachmentAttributes?.newHeight ?: videoInfo.height,
                        size = newAttachmentAttributes?.newFileSize ?: videoInfo.size
                )
        )
    }

    private fun MessageFileContent.update(
            url: String,
            encryptedFileInfo: EncryptedFileInfo?,
            size: Long
    ): MessageFileContent {
        return copy(
                url = if (encryptedFileInfo == null) url else null,
                encryptedFileInfo = encryptedFileInfo?.copy(url = url),
                info = info?.copy(size = size)
        )
    }

    private fun MessageAudioContent.update(
            url: String,
            encryptedFileInfo: EncryptedFileInfo?,
            size: Long
    ): MessageAudioContent {
        return copy(
                url = if (encryptedFileInfo == null) url else null,
                encryptedFileInfo = encryptedFileInfo?.copy(url = url),
                audioInfo = audioInfo?.copy(size = size)
        )
    }

    companion object {
        private const val MAX_IMAGE_SIZE = 640
    }
}
