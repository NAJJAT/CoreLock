package com.privacyguard.ui.mitm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.privacyguard.domain.repository.PayloadLogRepository
import com.privacyguard.vpn.mitm.MitmConfig
import com.privacyguard.vpn.mitm.PayloadShipper

class MitmViewModelFactory(
    private val mitmConfig: MitmConfig,
    private val payloadLogRepository: PayloadLogRepository,
    private val payloadShipper: PayloadShipper
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MitmViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MitmViewModel(mitmConfig, payloadLogRepository, payloadShipper) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}