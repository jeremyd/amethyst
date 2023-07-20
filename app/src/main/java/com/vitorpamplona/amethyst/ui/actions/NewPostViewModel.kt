package com.vitorpamplona.amethyst.ui.actions

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Stable
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
import com.vitorpamplona.amethyst.service.NostrSearchEventOrUserDataSource
import com.vitorpamplona.amethyst.service.model.AddressableEvent
import com.vitorpamplona.amethyst.service.model.BaseTextNoteEvent
import com.vitorpamplona.amethyst.service.model.CommunityDefinitionEvent
import com.vitorpamplona.amethyst.service.model.PrivateDmEvent
import com.vitorpamplona.amethyst.service.model.TextNoteEvent
import com.vitorpamplona.amethyst.service.noProtocolUrlValidator
import com.vitorpamplona.amethyst.ui.components.MediaCompressor
import com.vitorpamplona.amethyst.ui.components.isValidURL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

@Stable
open class NewPostViewModel() : ViewModel() {
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

    // NSFW, Sensitive
    var wantsToMarkAsSensitive by mutableStateOf(false)

    // ZapRaiser
    var canAddZapRaiser by mutableStateOf(false)
    var wantsZapraiser by mutableStateOf(false)
    var zapRaiserAmount by mutableStateOf<Long?>(null)

    open fun load(account: Account, replyingTo: Note?, quote: Note?) {
        originalNote = replyingTo
        replyingTo?.let { replyNote ->
            if (replyNote.event is BaseTextNoteEvent) {
                this.replyTos = (replyNote.replyTo ?: emptyList()).plus(replyNote)
            } else {
                this.replyTos = listOf(replyNote)
            }

            if (replyNote.event !is CommunityDefinitionEvent) {
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
        canAddZapRaiser = account.userProfile().info?.lnAddress() != null
        canUsePoll = originalNote?.event !is PrivateDmEvent && originalNote?.channelHex() == null
        contentToAddUrl = null

        wantsForwardZapTo = false
        wantsToMarkAsSensitive = false
        wantsZapraiser = false
        zapRaiserAmount = null
        forwardZapTo = null
        forwardZapToEditting = TextFieldValue("")

        this.account = account
    }

    fun sendPost() {
        val tagger = NewMessageTagger(originalNote?.channelHex(), mentions, replyTos, message.text)
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

        val localZapRaiserAmount = if (wantsZapraiser) zapRaiserAmount else null

        if (wantsPoll) {
            account?.sendPoll(tagger.message, tagger.replyTos, tagger.mentions, pollOptions, valueMaximum, valueMinimum, consensusThreshold, closedAt, zapReceiver, wantsToMarkAsSensitive, localZapRaiserAmount)
        } else if (originalNote?.channelHex() != null) {
            if (originalNote is AddressableEvent && originalNote?.address() != null) {
                account?.sendLiveMessage(tagger.message, originalNote?.address()!!, tagger.replyTos, tagger.mentions, zapReceiver, wantsToMarkAsSensitive, localZapRaiserAmount)
            } else {
                account?.sendChannelMessage(tagger.message, tagger.channelHex!!, tagger.replyTos, tagger.mentions, zapReceiver, wantsToMarkAsSensitive, localZapRaiserAmount)
            }
        } else if (originalNote?.event is PrivateDmEvent) {
            account?.sendPrivateMessage(tagger.message, originalNote!!.author!!, originalNote!!, tagger.mentions, zapReceiver, wantsToMarkAsSensitive, localZapRaiserAmount)
        } else {
            // adds markers
            val rootId =
                (originalNote?.event as? TextNoteEvent)?.root() // if it has a marker as root
                    ?: originalNote?.replyTo?.firstOrNull { it.event != null && it.replyTo?.isEmpty() == true }?.idHex // if it has loaded events with zero replies in the reply list
                    ?: originalNote?.replyTo?.firstOrNull()?.idHex // old rules, first item is root.
            val replyId = originalNote?.idHex

            account?.sendPost(
                message = tagger.message,
                replyTo = tagger.replyTos,
                mentions = tagger.mentions,
                tags = null,
                zapReceiver = zapReceiver,
                wantsToMarkAsSensitive = wantsToMarkAsSensitive,
                zapRaiserAmount = localZapRaiserAmount,
                replyingTo = replyId,
                root = rootId,
                directMentions = tagger.directMentions
            )
        }

        cancel()
    }

    fun upload(galleryUri: Uri, description: String, sensitiveContent: Boolean, server: ServersAvailable, context: Context) {
        isUploadingImage = true
        contentToAddUrl = null

        val contentResolver = context.contentResolver
        val contentType = contentResolver.getType(galleryUri)

        viewModelScope.launch(Dispatchers.IO) {
            MediaCompressor().compress(
                galleryUri,
                contentType,
                context.applicationContext,
                onReady = { fileUri, contentType, size ->
                    if (server == ServersAvailable.NIP95) {
                        contentResolver.openInputStream(fileUri)?.use {
                            createNIP95Record(it.readBytes(), contentType, description, sensitiveContent)
                        }
                    } else {
                        ImageUploader.uploadImage(
                            uri = fileUri,
                            contentType = contentType,
                            size = size,
                            server = server,
                            contentResolver = contentResolver,
                            onSuccess = { imageUrl, mimeType ->
                                if (isNIP94Server(server)) {
                                    createNIP94Record(imageUrl, mimeType, description, sensitiveContent)
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
                },
                onError = {
                    isUploadingImage = false
                    viewModelScope.launch {
                        imageUploadingError.emit(it)
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
        wantsZapraiser = false
        zapRaiserAmount = null

        wantsForwardZapTo = false
        wantsToMarkAsSensitive = false
        forwardZapTo = null
        forwardZapToEditting = TextFieldValue("")

        userSuggestions = emptyList()
        userSuggestionAnchor = null
        userSuggestionsMainMessage = null

        NostrSearchEventOrUserDataSource.clear()
    }

    open fun findUrlInMessage(): String? {
        return message.text.split('\n').firstNotNullOfOrNull { paragraph ->
            paragraph.split(' ').firstOrNull { word: String ->
                isValidURL(word) || noProtocolUrlValidator.matcher(word).matches()
            }
        }
    }

    open fun removeFromReplyList(userToRemove: User) {
        mentions = mentions?.filter { it != userToRemove }
    }

    open fun updateMessage(it: TextFieldValue) {
        message = it
        urlPreview = findUrlInMessage()

        if (it.selection.collapsed) {
            val lastWord = it.text.substring(0, it.selection.end).substringAfterLast("\n").substringAfterLast(" ")
            userSuggestionAnchor = it.selection
            userSuggestionsMainMessage = true
            if (lastWord.startsWith("@") && lastWord.length > 2) {
                NostrSearchEventOrUserDataSource.search(lastWord.removePrefix("@"))
                viewModelScope.launch(Dispatchers.IO) {
                    userSuggestions = LocalCache.findUsersStartingWith(lastWord.removePrefix("@"))
                        .sortedWith(compareBy({ account?.isFollowing(it) }, { it.toBestDisplayName() }))
                        .reversed()
                }
            } else {
                NostrSearchEventOrUserDataSource.clear()
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
                NostrSearchEventOrUserDataSource.search(lastWord.removePrefix("@"))
                viewModelScope.launch(Dispatchers.IO) {
                    userSuggestions = LocalCache.findUsersStartingWith(lastWord.removePrefix("@"))
                        .sortedWith(
                            compareBy(
                                { account?.isFollowing(it) },
                                { it.toBestDisplayName() }
                            )
                        ).reversed()
                }
            } else {
                NostrSearchEventOrUserDataSource.clear()
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
        return message.text.isNotBlank() && !isUploadingImage && !wantsInvoice && (!wantsZapraiser || zapRaiserAmount != null) && (!wantsPoll || pollOptions.values.all { it.isNotEmpty() }) && contentToAddUrl == null
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

    fun createNIP94Record(imageUrl: String, mimeType: String?, description: String, sensitiveContent: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            // Images don't seem to be ready immediately after upload
            FileHeader.prepare(
                imageUrl,
                mimeType,
                description,
                sensitiveContent,
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

    fun createNIP95Record(bytes: ByteArray, mimeType: String?, description: String, sensitiveContent: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            FileHeader.prepare(
                bytes,
                "",
                mimeType,
                description,
                sensitiveContent,
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
