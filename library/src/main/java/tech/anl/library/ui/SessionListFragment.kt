package tech.anl.library.ui

import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.* // ktlint-disable no-wildcard-imports
import android.widget.AdapterView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.navigation.fragment.findNavController
import tech.anl.library.databinding.FragSessionListBinding
import tech.anl.library.MainActivity
import tech.anl.library.R
import tech.anl.library.ServerService
import tech.anl.library.model.entities.Filesystem
import tech.anl.library.model.entities.ServiceType
import tech.anl.library.model.entities.Session
import tech.anl.library.model.repositories.AnlDatabase
import tech.anl.library.utils.NetworkAddresses
import tech.anl.library.utils.SshServerInfo
import tech.anl.library.utils.defaultSharedPreferences
import tech.anl.library.viewmodel.SessionListViewModel
import tech.anl.library.viewmodel.SessionListViewModelFactory

class SessionListFragment : Fragment() {

    private val fragSessionListBinding: FragSessionListBinding get() = FragSessionListBinding.bind(requireView())

    interface SessionSelection {
        fun sessionHasBeenSelected(session: Session)
    }

    private val doOnSessionSelection: SessionSelection by lazy {
        activityContext
    }

    private lateinit var activityContext: MainActivity

    private lateinit var sessionList: List<Session>
    private lateinit var sessionAdapter: SessionListAdapter
    private lateinit var filesystemList: List<Filesystem>

    private val sessionListViewModel: SessionListViewModel by lazy {
        val anlDatabase = AnlDatabase.getInstance(activityContext)
        ViewModelProvider(this, SessionListViewModelFactory(anlDatabase)).get(SessionListViewModel::class.java)
    }

    private val sessionsAndFilesystemsChangeObserver = Observer<Pair<List<Session>, List<Filesystem>>> {
        it?.let { pair ->
            sessionList = pair.first
            filesystemList = pair.second

            sessionAdapter = SessionListAdapter(activityContext, sessionList, filesystemList)
            fragSessionListBinding.listSessions.adapter = sessionAdapter
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.menu_create, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if (item.itemId == R.id.menu_item_add) editSession(Session(0, filesystemId = 0))
        else super.onOptionsItemSelected(item)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.frag_session_list, container, false)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        activityContext = requireActivity() as MainActivity

        sessionListViewModel.getSessionsAndFilesystems().observe(viewLifecycleOwner, sessionsAndFilesystemsChangeObserver)

        registerForContextMenu(fragSessionListBinding.listSessions)
        fragSessionListBinding.listSessions.onItemClickListener = AdapterView.OnItemClickListener {
            parent, _, position, _ ->
            when (val selectedItem = parent.getItemAtPosition(position) as SessionListItem) {
                is SessionSeparatorItem -> return@OnItemClickListener
                is SessionItem -> {
                    val session = selectedItem.session
                    doSessionItemClicked(session)
                }
            }
        }
    }

    private fun doSessionItemClicked(session: Session) {
        doOnSessionSelection.sessionHasBeenSelected(session)
    }

    override fun onCreateContextMenu(menu: ContextMenu, v: View, menuInfo: ContextMenu.ContextMenuInfo?) {
        super.onCreateContextMenu(menu, v, menuInfo)
        val info = menuInfo as AdapterView.AdapterContextMenuInfo
        val position = info.position
        when (val selectedItem = fragSessionListBinding.listSessions.adapter.getItem(position) as SessionListItem) {
            is SessionSeparatorItem -> return
            is SessionItem -> {
                val session = selectedItem.session
                doCreateSessionContextMenu(session, menu)
                if (session.isProtected)
                    menu.removeItem(R.id.menu_item_session_delete)
                if (session.serviceType != ServiceType.Ssh)
                    menu.removeItem(R.id.menu_item_session_copy_ssh)
            }
        }
    }

    private fun doCreateSessionContextMenu(session: Session, menu: ContextMenu) {
        when {
            session.active ->
                activityContext.menuInflater.inflate(R.menu.context_menu_active_sessions, menu)
            else ->
                activityContext.menuInflater.inflate(R.menu.context_menu_inactive_sessions, menu)
        }
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        val menuInfo = item.menuInfo as AdapterView.AdapterContextMenuInfo
        val position = menuInfo.position
        // The list may have been replaced by a LiveData update while the menu was open.
        val adapter = fragSessionListBinding.listSessions.adapter
        if (position < 0 || position >= adapter.count) return true
        return when (val selectedItem = adapter.getItem(position) as SessionListItem) {
            is SessionSeparatorItem -> true
            is SessionItem -> {
                val session = selectedItem.session
                doSessionContextItemSelected(session, item)
            }
        }
    }

    private fun doSessionContextItemSelected(session: Session, item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_item_session_stop_session -> stopService(session)
            R.id.menu_item_session_edit -> editSession(session)
            R.id.menu_item_session_delete -> deleteSession(session)
            R.id.menu_item_session_copy_ssh -> copySshCommand(session)
            else -> super.onContextItemSelected(item)
        }
    }

    private fun copySshCommand(session: Session): Boolean {
        val info = SshServerInfo.forSession(session, activityContext.defaultSharedPreferences, NetworkAddresses.lanAddress())
            ?: return true
        val clipboard = activityContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("ssh", info.command))
        // Android 13+ shows its own copy confirmation.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(activityContext, R.string.session_ssh_command_copied, Toast.LENGTH_SHORT).show()
        }
        return true
    }

    private fun stopService(session: Session): Boolean {
        if (session.active) {
            val serviceIntent = Intent(activityContext, ServerService::class.java)
            serviceIntent.putExtra("type", "kill")
            serviceIntent.putExtra("session", session)
            activityContext.startService(serviceIntent)
        }
        return true
    }

    private fun editSession(session: Session): Boolean {
        val editExisting = session.name != ""
        val bundle = bundleOf("session" to session, "editExisting" to editExisting)
        this.findNavController().navigate(R.id.session_edit_fragment, bundle)
        return true
    }

    private fun deleteSession(session: Session): Boolean {
        stopService(session)
        sessionListViewModel.deleteSessionById(session.id)
        return true
    }
}