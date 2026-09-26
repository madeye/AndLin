package tech.anl.library.ui

import android.app.AlertDialog
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.MenuInflater
import android.view.View
import android.view.ViewGroup
import android.view.LayoutInflater
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.fragment.navArgs
import tech.anl.library.databinding.FragFilesystemEditBinding
import tech.anl.library.MainActivity
import tech.anl.library.R
import tech.anl.library.model.repositories.AnlDatabase
import tech.anl.library.utils.CredentialValidator
import tech.anl.library.utils.DefaultCredentials
import tech.anl.library.utils.AnlFiles
import tech.anl.library.utils.preferences.AppsPreferences
import tech.anl.library.viewmodel.*
import java.util.Locale

class FilesystemEditFragment : Fragment() {

    private val fragFilesystemEditBinding: FragFilesystemEditBinding get() = FragFilesystemEditBinding.bind(requireView())

    private lateinit var activityContext: MainActivity

    private val IMPORT_FILESYSTEM_REQUEST_CODE = 5

    private val args: FilesystemEditFragmentArgs by navArgs()
    private val filesystem by lazy { args.filesystem!! }
    private val editExisting by lazy { args.editExisting }

    private val filesystemImportStatusObserver = Observer<FilesystemImportStatus> {
        it?.let { importStatus ->
            val dialogBuilder = AlertDialog.Builder(activityContext)
            when (importStatus) {
                is ImportSuccess -> dialogBuilder.setMessage(R.string.import_success).create().show()
                is ImportFailure -> dialogBuilder.setMessage(R.string.import_failure).create().show()
                else -> {}
            }
        }
    }

    private val filesystemEditViewModel: FilesystemEditViewModel by lazy {
        val anlDatabase = AnlDatabase.getInstance(activityContext)
        ViewModelProvider(this, FilesystemEditViewmodelFactory(anlDatabase)).get(FilesystemEditViewModel::class.java)
    }

    private val distributionList by lazy {
        AppsPreferences(activityContext).getDistributionsList()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.menu_edit, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if (item.itemId == R.id.menu_item_add) insertFilesystem()
        else super.onOptionsItemSelected(item)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.frag_filesystem_edit, container, false)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        activityContext = activity!! as MainActivity
        filesystemEditViewModel.getImportStatusLiveData().observe(viewLifecycleOwner, filesystemImportStatusObserver)

        if (distributionList.isNotEmpty()) {
            fragFilesystemEditBinding.spinnerFilesystemType.adapter = ArrayAdapter(activityContext,
                    android.R.layout.simple_spinner_dropdown_item,
                    distributionList.map { it.capitalize() })
        }
        if (editExisting) {
            for (i in 0 until fragFilesystemEditBinding.spinnerFilesystemType.adapter.count) {
                val item = fragFilesystemEditBinding.spinnerFilesystemType.adapter.getItem(i).toString().toLowerCase(Locale.ENGLISH)
                if (item == filesystem.distributionType) fragFilesystemEditBinding.spinnerFilesystemType.setSelection(i)
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupTextInputs()

        if (editExisting) {
            fragFilesystemEditBinding.btnShowAdvancedOptions.visibility = View.GONE
            fragFilesystemEditBinding.spinnerFilesystemType.isEnabled = false
            fragFilesystemEditBinding.filesystemProtected.isChecked = filesystem.isProtected
        } else {
            setupImportButton()
            setupAdvancedOptionButton()
            fragFilesystemEditBinding.filesystemProtected.isChecked = false
        }
        fragFilesystemEditBinding.spinnerFilesystemType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {

            override fun onNothingSelected(parent: AdapterView<*>?) {}

            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                filesystem.distributionType = parent?.getItemAtPosition(position).toString().toLowerCase(Locale.ENGLISH)
            }
        }
        fragFilesystemEditBinding.filesystemProtected.setOnCheckedChangeListener() { _, checked ->
            filesystem.isProtected = checked
        }
    }

    private fun setupTextInputs() {
        if (!editExisting && filesystem.defaultUsername.isEmpty() && filesystem.defaultPassword.isEmpty()) {
            val suggestedPassword = DefaultCredentials.randomPassword()
            filesystem.defaultUsername = DefaultCredentials.USERNAME
            filesystem.defaultPassword = suggestedPassword
            if (filesystem.defaultVncPassword.isEmpty()) filesystem.defaultVncPassword = suggestedPassword
        }
        fragFilesystemEditBinding.inputFilesystemName.setText(filesystem.name)
        fragFilesystemEditBinding.inputFilesystemUsername.setText(filesystem.defaultUsername)
        fragFilesystemEditBinding.inputFilesystemPassword.setText(filesystem.defaultPassword)
        fragFilesystemEditBinding.inputFilesystemVncpassword.setText(filesystem.defaultVncPassword)

        if (editExisting) {
            fragFilesystemEditBinding.inputFilesystemUsername.isEnabled = false
            fragFilesystemEditBinding.inputFilesystemPassword.isEnabled = false
            fragFilesystemEditBinding.inputFilesystemVncpassword.isEnabled = false
        }

        if (filesystem.isAppsFilesystem) {
            fragFilesystemEditBinding.inputFilesystemName.isEnabled = false
        }

        fragFilesystemEditBinding.inputFilesystemName.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(p0: Editable?) {
                filesystem.name = p0.toString()
            }

            override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
        })

        fragFilesystemEditBinding.inputFilesystemUsername.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(p0: Editable?) {

                filesystem.defaultUsername = p0.toString()
            }

            override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
        })

        fragFilesystemEditBinding.inputFilesystemPassword.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(p0: Editable?) {
                filesystem.defaultPassword = p0.toString()
            }

            override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
        })

        fragFilesystemEditBinding.inputFilesystemVncpassword.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(p0: Editable?) {
                filesystem.defaultVncPassword = p0.toString()
            }

            override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
        })
    }

    private fun setupImportButton() {
        fragFilesystemEditBinding.importButton.setOnClickListener {
            val filePickerIntent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            filePickerIntent.addCategory(Intent.CATEGORY_OPENABLE)
            filePickerIntent.type = "application/*"

            try {
                filesystem.isCreatedFromBackup = true
                startActivityForResult(filePickerIntent, IMPORT_FILESYSTEM_REQUEST_CODE)
            } catch (activityNotFoundErr: ActivityNotFoundException) {
                Toast.makeText(activityContext, R.string.prompt_install_file_manager, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setupAdvancedOptionButton() {
        val btn = fragFilesystemEditBinding.btnShowAdvancedOptions

        btn.setOnClickListener {
            when (btn.isChecked) {
                true -> {
                    btn.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_keyboard_arrow_down_white_24dp, 0)
                    fragFilesystemEditBinding.advancedOptions.visibility = View.VISIBLE
                }
                false -> {
                    btn.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_keyboard_arrow_right_white_24dp, 0)
                    fragFilesystemEditBinding.advancedOptions.visibility = View.INVISIBLE
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, returnIntent: Intent?) {
        super.onActivityResult(requestCode, resultCode, returnIntent)
        if (requestCode == IMPORT_FILESYSTEM_REQUEST_CODE) {
            returnIntent?.data?.let { uri ->
                filesystemEditViewModel.backupUri = uri
                fragFilesystemEditBinding.textBackupFilename.text = uri.lastPathSegment
            }
        }
    }

    private fun insertFilesystem(): Boolean {
        val navController = NavHostFragment.findNavController(this)
        if (!filesystemParametersAreCorrect()) {
            return false
        }

        if (editExisting) {
            filesystemEditViewModel.updateFilesystem(filesystem)
            navController.popBackStack()
        } else {
            val anlFiles = AnlFiles(activityContext, activityContext.applicationInfo.nativeLibraryDir)
            filesystem.archType = anlFiles.getArchType()
            if (filesystem.isCreatedFromBackup) {
                filesystemEditViewModel.insertFilesystemFromBackup(activityContext.contentResolver, filesystem, activityContext.filesDir)
            } else {
                filesystemEditViewModel.insertFilesystem(filesystem)
            }
            navController.popBackStack()
        }

        return true
    }

    private fun filesystemParametersAreCorrect(): Boolean {
        val blacklistedUsernames = activityContext.resources.getStringArray(R.array.blacklisted_usernames)
        val validator = CredentialValidator()
        val filesystemName = filesystem.name
        val username = filesystem.defaultUsername
        val password = filesystem.defaultPassword
        val vncPassword = filesystem.defaultVncPassword

        val filesystemNameCredentials = validator.validateFilesystemName(filesystemName)
        val usernameCredentials = validator.validateUsername(username, blacklistedUsernames)
        val passwordCredentials = validator.validatePassword(password)
        val vncPasswordCredentials = validator.validateVncPassword(vncPassword)

        when {
            !filesystemNameCredentials.credentialIsValid ->
                Toast.makeText(activityContext, filesystemNameCredentials.errorMessageId, Toast.LENGTH_LONG).show()
            !usernameCredentials.credentialIsValid ->
                Toast.makeText(activityContext, usernameCredentials.errorMessageId, Toast.LENGTH_LONG).show()
            !passwordCredentials.credentialIsValid ->
                Toast.makeText(activityContext, passwordCredentials.errorMessageId, Toast.LENGTH_LONG).show()
            !vncPasswordCredentials.credentialIsValid ->
                Toast.makeText(activityContext, vncPasswordCredentials.errorMessageId, Toast.LENGTH_LONG).show()
            else ->
                return true
        }
        return false
    }
}