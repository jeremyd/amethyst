package com.vitorpamplona.amethyst.ui.actions

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vitorpamplona.amethyst.model.*
import com.vitorpamplona.amethyst.service.FileHeader
import com.vitorpamplona.amethyst.service.model.PrivateDmEvent
import com.vitorpamplona.amethyst.service.model.TextNoteEvent
import com.vitorpamplona.amethyst.ui.components.isValidURL
import com.vitorpamplona.amethyst.ui.components.noProtocolUrlValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

open class NewPostViewModel : ViewModel() {
    var account: Account? = null
    var originalNote: Note? = null

    var mentions by mutableStateOf<List<User>?>(null)
    var replyTos by mutableStateOf<List<Note>?>(null)

    var message by mutableStateOf(TextFieldValue(""))
    var urlPreview by mutableStateOf<String?>(null)
    var isUploadingImage by mutableStateOf(false)
    val imageUploadingError = MutableSharedFlow<String?>()

    var userSuggestions by mutableStateOf<List<User>>(emptyList())
    var userSuggestionAnchor: TextRange? = null
    var userSuggestionsMainMessage: Boolean? = null

    // Images and Videos
    var contentToAddUrl by mutableStateOf<Uri?>(null)

    // Polls
    var canUsePoll by mutableStateOf(false)
    var wantsPoll by mutableStateOf(false)
    var zapRecipients = mutableStateListOf<HexKey>()
    var pollOptions = newStateMapPollOptions()
    var valueMaximum: Int? = null
    var valueMinimum: Int? = null
    var consensusThreshold: Int? = null
    var closedAt: Int? = null

    var isValidRecipients = mutableStateOf(true)
    var isValidvalueMaximum = mutableStateOf(true)
    var isValidvalueMinimum = mutableStateOf(true)
    var isValidConsensusThreshold = mutableStateOf(true)
    var isValidClosedAt = mutableStateOf(true)

    // Invoices
    var canAddInvoice by mutableStateOf(false)
    var wantsInvoice by mutableStateOf(false)

    // Forward Zap to
    var wantsForwardZapTo by mutableStateOf(false)
    var forwardZapTo by mutableStateOf<User?>(null)
    var forwardZapToEditting by mutableStateOf(TextFieldValue(""))

    open fun load(account: Account, replyingTo: Note?, quote: Note?) {
        originalNote = replyingTo
        replyingTo?.let { replyNote ->
            this.replyTos = (replyNote.replyTo ?: emptyList()).plus(replyNote)
            replyNote.author?.let { replyUser ->
                val currentMentions = (replyNote.event as? TextNoteEvent)
                    ?.mentions()
                    ?.map { LocalCache.getOrCreateUser(it) } ?: emptyList()

                if (currentMentions.contains(replyUser)) {
                    this.mentions = currentMentions
                } else {
                    this.mentions = currentMentions.plus(replyUser)
                }
            }
        } ?: run {
            replyTos = null
            mentions = null
        }

        quote?.let {
            message = TextFieldValue(message.text + "\n\nnostr:${it.toNEvent()}")
            urlPreview = findUrlInMessage()
        }

        canAddInvoice = account.userProfile().info?.lnAddress() != null
        canUsePoll = originalNote?.event !is PrivateDmEvent && originalNote?.channel() == null
        contentToAddUrl = null

        wantsForwardZapTo = false
        forwardZapTo = null
        forwardZapToEditting = TextFieldValue("")

        this.account = account
    }

    fun sendPost() {
        val tagger = NewMessageTagger(originalNote?.channel(), mentions, replyTos, message.text)
        tagger.run()

        val zapReceiver = if (wantsForwardZapTo) {
            if (forwardZapTo != null) {
                forwardZapTo?.info?.lud16 ?: forwardZapTo?.info?.lud06
            } else {
                forwardZapToEditting.text
            }
        } else {
            null
        }

        if (wantsPoll) {
            account?.sendPoll(tagger.message, tagger.replyTos, tagger.mentions, pollOptions, valueMaximum, valueMinimum, consensusThreshold, closedAt, zapReceiver)
        } else if (originalNote?.channel() != null) {
            account?.sendChannelMessage(tagger.message, tagger.channel!!.idHex, tagger.replyTos, tagger.mentions, zapReceiver)
        } else if (originalNote?.event is PrivateDmEvent) {
            account?.sendPrivateMessage(tagger.message, originalNote!!.author!!.pubkeyHex, originalNote!!, tagger.mentions, zapReceiver)
        } else {
            account?.sendPost(tagger.message, tagger.replyTos, tagger.mentions, null, zapReceiver)
        }

        cancel()
    }

    fun upload(it: Uri, description: String, server: ServersAvailable, context: Context) {
        isUploadingImage = true
        contentToAddUrl = null

        val contentResolver = context.contentResolver

        if (server == ServersAvailable.NIP95) {
            val contentType = contentResolver.getType(it)
            contentResolver.openInputStream(it)?.use {
                createNIP95Record(it.readBytes(), contentType, description)
            }
        } else {
            ImageUploader.uploadImage(
                uri = it,
                server = server,
                context = context,
                contentResolver = contentResolver,
                onSuccess = { imageUrl, mimeType ->
                    if (server == ServersAvailable.IMGUR_NIP_94 || server == ServersAvailable.NOSTRIMG_NIP_94 || server == ServersAvailable.NOSTR_BUILD_NIP_94) {
                        createNIP94Record(imageUrl, mimeType, description)
                    } else {
                        isUploadingImage = false
                        message = TextFieldValue(message.text + "\n\n" + imageUrl)
                        urlPreview = findUrlInMessage()
                    }
                },
                onError = {
                    isUploadingImage = false
                    viewModelScope.launch {
                        imageUploadingError.emit("Failed to upload the image / video")
                    }
                }
            )
        }
    }

    open fun cancel() {
        message = TextFieldValue("")
        contentToAddUrl = null
        urlPreview = null
        isUploadingImage = false
        mentions = null

        wantsPoll = false
        zapRecipients = mutableStateListOf<HexKey>()
        pollOptions = newStateMapPollOptions()
        valueMaximum = null
        valueMinimum = null
        consensusThreshold = null
        closedAt = null

        wantsInvoice = false

        wantsForwardZapTo = false
        forwardZapTo = null
        forwardZapToEditting = TextFieldValue("")

        userSuggestions = emptyList()
        userSuggestionAnchor = null
        userSuggestionsMainMessage = null
    }

    open fun findUrlInMessage(): String? {
        return message.text.split('\n').firstNotNullOfOrNull { paragraph ->
            paragraph.split(' ').firstOrNull { word: String ->
                isValidURL(word) || noProtocolUrlValidator.matcher(word).matches()
            }
        }
    }

    open fun removeFromReplyList(it: User) {
        mentions = mentions?.minus(it)
    }

    open fun updateMessage(it: TextFieldValue) {
        message = it
        urlPreview = findUrlInMessage()

        if (it.selection.collapsed) {
            val lastWord = it.text.substring(0, it.selection.end).substringAfterLast("\n").substringAfterLast(" ")
            userSuggestionAnchor = it.selection
            userSuggestionsMainMessage = true
            if (lastWord.startsWith("@") && lastWord.length > 2) {
                userSuggestions = LocalCache.findUsersStartingWith(lastWord.removePrefix("@"))
            } else {
                userSuggestions = emptyList()
            }
        }
    }

    open fun updateZapForwardTo(it: TextFieldValue) {
        forwardZapToEditting = it
        if (it.selection.collapsed) {
            val lastWord = it.text.substring(0, it.selection.end).substringAfterLast("\n").substringAfterLast(" ")
            userSuggestionAnchor = it.selection
            userSuggestionsMainMessage = false
            if (lastWord.startsWith("@") && lastWord.length > 2) {
                userSuggestions = LocalCache.findUsersStartingWith(lastWord.removePrefix("@"))
            } else {
                userSuggestions = emptyList()
            }
        }
    }

    open fun autocompleteWithUser(item: User) {
        userSuggestionAnchor?.let {
            if (userSuggestionsMainMessage == true) {
                val lastWord = message.text.substring(0, it.end).substringAfterLast("\n").substringAfterLast(" ")
                val lastWordStart = it.end - lastWord.length
                val wordToInsert = "@${item.pubkeyNpub()}"

                message = TextFieldValue(
                    message.text.replaceRange(lastWordStart, it.end, wordToInsert),
                    TextRange(lastWordStart + wordToInsert.length, lastWordStart + wordToInsert.length)
                )
            } else {
                val lastWord = forwardZapToEditting.text.substring(0, it.end).substringAfterLast("\n").substringAfterLast(" ")
                val lastWordStart = it.end - lastWord.length
                val wordToInsert = "@${item.pubkeyNpub()}"
                forwardZapTo = item

                forwardZapToEditting = TextFieldValue(
                    forwardZapToEditting.text.replaceRange(lastWordStart, it.end, wordToInsert),
                    TextRange(lastWordStart + wordToInsert.length, lastWordStart + wordToInsert.length)
                )
            }

            userSuggestionAnchor = null
            userSuggestionsMainMessage = null
            userSuggestions = emptyList()
        }
    }

    private fun newStateMapPollOptions(): SnapshotStateMap<Int, String> {
        return mutableStateMapOf(Pair(0, ""), Pair(1, ""))
    }

    fun canPost(): Boolean {
        return message.text.isNotBlank() && !isUploadingImage && !wantsInvoice &&
            (!wantsPoll || pollOptions.values.all { it.isNotEmpty() }) && contentToAddUrl == null
    }

    fun includePollHashtagInMessage(include: Boolean, hashtag: String) {
        if (include) {
            updateMessage(TextFieldValue(message.text + " $hashtag"))
        } else {
            updateMessage(
                TextFieldValue(
                    message.text.replace(" $hashtag", "")
                        .replace(hashtag, "")
                )
            )
        }
    }

    fun createNIP94Record(imageUrl: String, mimeType: String?, description: String) {
        viewModelScope.launch(Dispatchers.IO) {
            // Images don't seem to be ready immediately after upload

            if (mimeType?.startsWith("image/") == true) {
                delay(2000)
            } else {
                delay(5000)
            }

            FileHeader.prepare(
                imageUrl,
                mimeType,
                description,
                onReady = {
                    val note = account?.sendHeader(it)

                    isUploadingImage = false

                    if (note == null) {
                        message = TextFieldValue(message.text + "\n\n" + imageUrl + "\n\n" + imageUrl)
                    } else {
                        message = TextFieldValue(message.text + "\n\nnostr:" + note.toNEvent() + "\n\n" + imageUrl)
                    }

                    urlPreview = findUrlInMessage()
                },
                onError = {
                    isUploadingImage = false
                    viewModelScope.launch {
                        imageUploadingError.emit("Failed to upload the image / video")
                    }
                }
            )
        }
    }

    fun createNIP95Record(bytes: ByteArray, mimeType: String?, description: String) {
        viewModelScope.launch(Dispatchers.IO) {
            FileHeader.prepare(
                bytes,
                "",
                mimeType,
                description,
                onReady = {
                    val nip95 = account?.createNip95(bytes, headerInfo = it)
                    val note = nip95?.let { it1 -> account?.sendNip95(it1.first, it1.second) }

                    isUploadingImage = false

                    note?.let {
                        message = TextFieldValue(message.text + "\n\nnostr:" + it.toNEvent())
                    }

                    urlPreview = findUrlInMessage()
                },
                onError = {
                    isUploadingImage = false
                    viewModelScope.launch {
                        imageUploadingError.emit("Failed to upload the image / video")
                    }
                }
            )
        }
    }

    fun selectImage(uri: Uri) {
        contentToAddUrl = uri
    }
}
