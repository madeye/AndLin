package tech.anl.library.ui

import android.app.Activity
import android.content.Context
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.navArgs
import tech.anl.library.databinding.FragAppDetailsBinding
import tech.anl.library.R
import tech.anl.library.model.repositories.AnlDatabase
import tech.anl.library.utils.* // ktlint-disable no-wildcard-imports
import tech.anl.library.viewmodel.AppDetailsEvent
import tech.anl.library.viewmodel.AppDetailsViewModel
import tech.anl.library.viewmodel.AppDetailsViewState
import tech.anl.library.viewmodel.AppDetailsViewmodelFactory

class AppDetailsFragment : Fragment() {

    private val fragAppDetailsBinding: FragAppDetailsBinding get() = FragAppDetailsBinding.bind(requireView())

    private lateinit var activityContext: Activity

    private val args: AppDetailsFragmentArgs by navArgs()
    private val app by lazy { args.app!! }

    private val viewModel by lazy {
        val sessionDao = AnlDatabase.getInstance(activityContext).sessionDao()
        val appDetails = AppDetails(activityContext.filesDir.path, activityContext.resources)
        val factory = AppDetailsViewmodelFactory(sessionDao, appDetails, activityContext.getSharedPreferences("apps", Context.MODE_PRIVATE))
        ViewModelProvider(this, factory)                .get(AppDetailsViewModel::class.java)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.frag_app_details, container, false)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        activityContext = requireActivity()
        viewModel.viewState.observe(viewLifecycleOwner, Observer<AppDetailsViewState> { viewState ->
            viewState?.let {
                handleViewStateChange(viewState)
            }
        })
        viewModel.submitEvent(AppDetailsEvent.SubmitApp(app))
        setupAutoStartCheckbox()
    }

    private fun handleViewStateChange(viewState: AppDetailsViewState) {
        fragAppDetailsBinding.appsIcon.setImageURI(viewState.appIconUri)
        fragAppDetailsBinding.appsTitle.text = viewState.appTitle
        fragAppDetailsBinding.appsDescription.text = viewState.appDescription
        handleShowStateHint(viewState)

        fragAppDetailsBinding.checkboxAutoStart.setChecked(viewState.autoStartEnabled)
    }

    private fun handleShowStateHint(viewState: AppDetailsViewState) {
        if (viewState.describeStateHintEnabled) {
            fragAppDetailsBinding.textDescribeState.visibility = View.VISIBLE
            fragAppDetailsBinding.textDescribeState.setText(viewState.describeStateText!!)
        } else {
            fragAppDetailsBinding.textDescribeState.visibility = View.GONE
        }
    }

    private fun setupAutoStartCheckbox() {
        fragAppDetailsBinding.checkboxAutoStart.setOnCheckedChangeListener { _, checked ->
            viewModel.submitEvent(AppDetailsEvent.AutoStartChanged(checked, app))
        }
    }
}