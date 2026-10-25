package com.dcode.wallet

import android.app.Application
import com.dcode.wallet.data.CardRepository

class WalletApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CardRepository.init(this)
    }
}
