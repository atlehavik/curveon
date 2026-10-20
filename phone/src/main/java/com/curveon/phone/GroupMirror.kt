package com.curveon.phone

import com.curveon.protocol.Message
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the companion shows of the rider's friends and groups. They live in the Curveon app on the tablet (or on this phone),
 * so the companion owns nothing: it is told how the active group is doing, asks the tablet for a friend code, and hands over
 * what it scans. With no tablet linked there is nothing to show or hand over to ([Message.GroupErrorCode.NOT_LINKED]).
 */
object GroupMirror {
    data class Summary(val groupId: String, val name: String?, val status: Message.GroupStatus, val members: Int)

    private val _summary = MutableStateFlow<Summary?>(null)
    val summary: StateFlow<Summary?> = _summary.asStateFlow()

    /** The friend code the tablet made when asked: null until it arrives. */
    private val _friendInvite = MutableStateFlow<String?>(null)
    val friendInvite: StateFlow<String?> = _friendInvite.asStateFlow()

    private val _errors = MutableSharedFlow<Message.GroupErrorCode>(extraBufferCapacity = 4)
    val errors: SharedFlow<Message.GroupErrorCode> = _errors.asSharedFlow()

    private var list: Message.GroupList? = null
    private var connection: Message.GroupConnection? = null

    /** The messages the tablet sends the companion about its groups; true if [m] was one. */
    @Synchronized
    fun onMessage(m: Message): Boolean {
        when (m) {
            is Message.GroupList -> { list = m; update() }
            is Message.GroupConnection -> { connection = m; update() }
            is Message.FriendInvite -> _friendInvite.value = m.uri
            is Message.GroupError -> _errors.tryEmit(m.code)
            else -> return false
        }
        return true
    }

    /** The tablet went away: what it said is old. */
    @Synchronized
    fun clear() {
        list = null
        connection = null
        _summary.value = null
        _friendInvite.value = null
    }

    /** Asks the tablet for a friend code ([friendInvite] gets it); says so if there is no tablet to ask. */
    fun requestFriendInvite() {
        _friendInvite.value = null
        tell(Message.RequestFriendInvite)
    }

    fun forgetFriendInvite() { _friendInvite.value = null }

    /** Hands a scanned or opened invite to the tablet, which joins. */
    fun join(invite: String) = tell(Message.GroupJoin(invite))

    private fun tell(m: Message) {
        if (PhoneHub.tabletCount == 0) _errors.tryEmit(Message.GroupErrorCode.NOT_LINKED) else PhoneHub.broadcast(m)
    }

    private fun update() {
        val c = connection
        val name = list?.groups?.firstOrNull { it.id == c?.groupId }?.name
        _summary.value = if (c == null || list?.activeId == null) null else Summary(c.groupId, name, c.status, c.members)
    }
}
