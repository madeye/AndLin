package tech.anl.library.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import tech.anl.library.databinding.FragHelpBinding
import tech.anl.library.R

class HelpFragment : Fragment() {

    private val fragHelpBinding: FragHelpBinding get() = FragHelpBinding.bind(requireView())

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.frag_help, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        fragHelpBinding.githubLogo.setOnClickListener {
            val intent = Intent("android.intent.action.VIEW", Uri.parse("https://github.com/madeye/ServerBox/issues"))
            startActivity(intent)
        }

        fragHelpBinding.websiteLogo.setOnClickListener {
            val intent = Intent("android.intent.action.VIEW", Uri.parse("https://github.com/madeye/ServerBox"))
            startActivity(intent)
        }
    }
}