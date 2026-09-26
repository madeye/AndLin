package tech.anl.library.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Toast
import tech.anl.library.R
import tech.anl.library.proot.DroidFilesConsent
import tech.anl.library.proot.DroidFilesGrants

/**
 * Translucent trampoline that asks the user (ACTION_OPEN_DOCUMENT_TREE) for access to one
 * top-level shared-storage directory on behalf of the droid_files server, persists the grant and
 * reports the answer through [DroidFilesConsent].
 */
class DroidFilesPermissionActivity : Activity() {
    private lateinit var topLevelDir: String
    private var delivered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val top = intent.getStringExtra(DroidFilesConsent.EXTRA_TOP)
        if (top.isNullOrEmpty()) {
            finish()
            return
        }
        topLevelDir = top
        delivered = savedInstanceState?.getBoolean(STATE_DELIVERED) ?: false
        // After a configuration change the picker is still up; don't open a second one.
        if (savedInstanceState == null) launchPicker()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_DELIVERED, delivered)
    }

    private fun launchPicker() {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                putExtra(
                    DocumentsContract.EXTRA_INITIAL_URI,
                    DocumentsContract.buildDocumentUri(
                        DroidFilesGrants.EXTERNAL_STORAGE_AUTHORITY,
                        DroidFilesGrants.documentIdFor(topLevelDir),
                    ),
                )
            }
        }
        Toast.makeText(this, getString(R.string.droid_files_pick_folder, topLevelDir), Toast.LENGTH_LONG).show()
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(picker, REQUEST_TREE)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.droid_files_no_picker, Toast.LENGTH_LONG).show()
            deliver(null)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_TREE) return
        val treeUri = if (resultCode == RESULT_OK) data?.data else null
        if (treeUri == null) {
            Toast.makeText(this, getString(R.string.droid_files_declined, topLevelDir), Toast.LENGTH_LONG).show()
            deliver(null)
            return
        }
        val root = DroidFilesGrants.relativeRootOf(treeUri)
        if (root == null) {
            Toast.makeText(this, R.string.droid_files_wrong_volume, Toast.LENGTH_LONG).show()
            deliver(null)
            return
        }
        try {
            contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            deliver(null)
            return
        }
        DroidFilesGrants(this).put(root, treeUri)
        if (DroidFilesGrants.coveringRoot(listOf(root), topLevelDir) == null &&
            DroidFilesGrants.coveringRoot(listOf(topLevelDir), root) == null
        ) {
            // Picked something unrelated; the grant is kept, but it does not answer this request.
            Toast.makeText(this, getString(R.string.droid_files_other_folder, topLevelDir), Toast.LENGTH_LONG).show()
        }
        deliver(treeUri)
    }

    private fun deliver(treeUri: Uri?) {
        if (!delivered) {
            delivered = true
            DroidFilesConsent.complete(topLevelDir, treeUri)
        }
        finish()
    }

    override fun onDestroy() {
        if (isFinishing && !delivered && ::topLevelDir.isInitialized) {
            delivered = true
            DroidFilesConsent.complete(topLevelDir, null)
        }
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_TREE = 0x0d0f
        private const val STATE_DELIVERED = "delivered"

        fun intent(context: Context, topLevelDir: String): Intent =
            Intent(context, DroidFilesPermissionActivity::class.java)
                .putExtra(DroidFilesConsent.EXTRA_TOP, topLevelDir)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}
