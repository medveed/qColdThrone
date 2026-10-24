package app.qcoldthrone.mobile.ui

import android.content.Context
import android.content.Intent
import android.view.Menu
import android.view.MenuItem
import app.qcoldthrone.mobile.Action
import app.qcoldthrone.mobile.R
import app.qcoldthrone.mobile.SagerNet
import app.qcoldthrone.mobile.database.DataStore
import app.qcoldthrone.mobile.database.ProfileManager
import app.qcoldthrone.mobile.database.ProfileOrder
import app.qcoldthrone.mobile.ktx.runOnDefaultDispatcher

/** Android TV has no notification shade or widgets: previous / next server sit in the profiles toolbar there. */
object TvControls {

    fun addServerSwitch(menu: Menu, groupId: Int) {
        if (!SagerNet.isTv) return
        menu.add(groupId, R.id.action_previous_server, Menu.NONE, R.string.tv_previous_server)
            .setIcon(R.drawable.ic_toolbar_previous)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(groupId, R.id.action_next_server, Menu.NONE, R.string.tv_next_server)
            .setIcon(R.drawable.ic_toolbar_next)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
    }

    fun onMenuItemClick(context: Context, item: MenuItem): Boolean {
        val step = when (item.itemId) {
            R.id.action_previous_server -> -1
            R.id.action_next_server -> 1
            else -> return false
        }
        switchServer(context, step)
        return true
    }

    /** The notification's path: a running service switches in place (ProfileOrder), a stopped one moves the selection. */
    fun switchServer(context: Context, step: Int) {
        if (DataStore.serviceState.canStop) {
            val action = if (step > 0) Action.SWITCH_NEXT else Action.SWITCH_PREVIOUS
            context.sendBroadcast(Intent(action).setPackage(context.packageName))
            return
        }
        runOnDefaultDispatcher {
            val old = DataStore.selectedProxy
            val target = ProfileOrder.neighbour(old, step) ?: return@runOnDefaultDispatcher
            DataStore.selectedProxy = target.id
            ProfileManager.postUpdate(old, true)
            ProfileManager.postUpdate(target.id, true)
        }
    }
}
