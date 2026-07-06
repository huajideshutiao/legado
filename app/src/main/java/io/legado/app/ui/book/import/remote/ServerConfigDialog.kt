package io.legado.app.ui.book.import.remote

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.entities.Server
import io.legado.app.databinding.DialogWebdavServerBinding
import io.legado.app.utils.GSON
import io.legado.app.utils.viewbindingdelegate.viewBinding
import org.json.JSONObject

class ServerConfigDialog() : BaseDialogFragment(R.layout.dialog_webdav_server),
    Toolbar.OnMenuItemClickListener {

    constructor(id: Long) : this() {
        arguments = Bundle().apply {
            putLong("id", id)
        }
    }

    private val binding by viewBinding(DialogWebdavServerBinding::bind)
    private val viewModel by viewModels<ServerConfigViewModel>()

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        setupTitleBar(
            menuRes = R.menu.server_config,
            onMenuClick = ::onMenuItemClick
        )
        viewModel.init(arguments?.getLong("id")) {
            upConfigView(viewModel.mServer)
        }
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.menu_save -> getServer().let {
                viewModel.save(it) {
                    dismissAllowingStateLoss()
                }
            }
        }
        return true
    }

    private fun upConfigView(server: Server?) {
        binding.etName.setText(server?.name)
        binding.spType.setSelection(
            when (server?.type) {
                else -> 0
            }
        )
        when (server?.type) {
            else -> upWebDavServerUi(server?.getConfigJsonObject())
        }
    }

    private fun upWebDavServerUi(config: JSONObject?) {
        binding.etUrl.setText(config?.getString("url"))
        binding.etUsername.setText(config?.getString("username"))
        binding.etPassword.setText(config?.getString("password"))
    }

    private fun getServer(): Server {
        val server = viewModel.mServer?.copy() ?: Server()
        server.name = binding.etName.text.toString()
        server.type = when (binding.spType.selectedItemPosition) {
            else -> Server.TYPE.WEBDAV
        }
        server.config = when (server.type) {
            else -> GSON.toJson(getWebDavConfig())
        }
        return server
    }

    private fun getWebDavConfig(): HashMap<String, String> {
        val data = hashMapOf<String, String>()
        binding.etUrl.text?.let { data["url"] = it.toString() }
        binding.etUsername.text?.let { data["username"] = it.toString() }
        binding.etPassword.text?.let { data["password"] = it.toString() }
        return data
    }

}
