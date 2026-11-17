package com.chaoswares.chaosplay.network

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.io.InputStream

/**
 * Media3 data source for SMB / FTP / SFTP / NFS (spec 19.3 "Android: Media3
 * has no built-in SMB, so write a custom DataSource"). Each open() starts at
 * the requested byte, so seeking in a 20 GB MKV only reads what is played.
 */
@OptIn(UnstableApi::class)
class ShareDataSource(private val loginFor: (String) -> ShareLogin) : BaseDataSource(/* isNetwork = */ true) {

    private var uri: Uri? = null
    private var stream: InputStream? = null
    private var bytesRemaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val parsed = RemoteUris.parse(dataSpec.uri.toString())
            ?: throw DataSourceException("Unsupported share location", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        val login = try {
            loginFor(parsed.profileId)
        } catch (e: Exception) {
            throw DataSourceException(e.message ?: "Server not found", e, PlaybackException.ERROR_CODE_IO_NO_PERMISSION)
        }
        try {
            val length = RemoteFsPool.withRetry(login) { it.length(parsed.path) }
            if (length >= 0 && dataSpec.position > length) {
                throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
            }
            stream = RemoteFsPool.withRetry(login) { it.open(parsed.path, dataSpec.position) }
            bytesRemaining = when {
                dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
                length >= 0 -> length - dataSpec.position
                else -> C.LENGTH_UNSET.toLong()
            }
        } catch (e: DataSourceException) {
            throw e
        } catch (e: NetworkError) {
            throw DataSourceException(e.message, e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        } catch (e: IOException) {
            throw DataSourceException(RemoteFsPool.friendly(e, login.profile.type), e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val toRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) length else minOf(length.toLong(), bytesRemaining).toInt()
        val n = try {
            stream?.read(buffer, offset, toRead) ?: -1
        } catch (e: IOException) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }
        if (n == -1) return C.RESULT_END_OF_INPUT
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try {
            stream?.close()
        } catch (_: IOException) {
        } finally {
            stream = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
}

/** Sends share locations to [ShareDataSource] and everything else to Media3's default source. */
@OptIn(UnstableApi::class)
class RoutingDataSource(private val fallback: DataSource, private val shares: DataSource) : DataSource {

    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        fallback.addTransferListener(transferListener)
        shares.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val target = if (RemoteUris.isRemote(dataSpec.uri.toString())) shares else fallback
        active = target
        return target.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        active?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            active?.close()
        } finally {
            active = null
        }
    }
}

/** The data source every player uses: files, content://, http(s) with credentials, and shares. */
@OptIn(UnstableApi::class)
fun chaosDataSourceFactory(context: Context): DataSource.Factory {
    val app = context.applicationContext
    val default = DefaultDataSource.Factory(app, NetHttp.mediaDataSourceFactory())
    val repo = NetworkRepository.get(app)
    return DataSource.Factory {
        RoutingDataSource(default.createDataSource(), ShareDataSource { id -> repo.shareLoginBlocking(id) })
    }
}
