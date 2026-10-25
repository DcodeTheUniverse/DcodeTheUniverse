package com.dcode.wallet.autofill

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.autofill.Dataset
import android.view.View
import android.view.WindowManager
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import androidx.fragment.app.FragmentActivity
import com.dcode.wallet.R
import com.dcode.wallet.data.Card
import com.dcode.wallet.data.CardRepository
import com.dcode.wallet.security.Auth
import java.time.YearMonth
import java.time.ZoneId

/** Verifies the user, then hands the unlocked card details back to the autofill framework. */
class AutofillAuthActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        if (savedInstanceState != null) return

        CardRepository.init(this)
        val card = intent.getStringExtra(EXTRA_CARD_ID)?.let { CardRepository.state.value.card(it) }
        if (card == null || !Auth.isAvailable(this)) {
            cancel()
            return
        }
        Auth.authenticate(
            activity = this,
            title = "Pay with ${card.title} •••• ${card.last4}",
            subtitle = "Confirm it's you to fill your card details",
            onFailure = { cancel() },
        ) { deliver(card) }
    }

    private fun deliver(card: Card) {
        val ids: List<AutofillId> = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayListExtra(EXTRA_IDS, AutofillId::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra(EXTRA_IDS)
        } ?: emptyList()
        val fieldTypes = intent.getIntArrayExtra(EXTRA_FIELD_TYPES) ?: IntArray(0)
        val autofillTypes = intent.getIntArrayExtra(EXTRA_AUTOFILL_TYPES) ?: IntArray(0)
        val maxLengths = intent.getIntArrayExtra(EXTRA_MAX_LENGTHS) ?: IntArray(0)

        val presentation = RemoteViews(packageName, R.layout.autofill_item).apply {
            setTextViewText(R.id.title, "${card.title} •••• ${card.last4}")
        }
        @Suppress("DEPRECATION")
        val builder = Dataset.Builder(presentation)
        var filled = 0
        ids.forEachIndexed { i, id ->
            val field = CardField.entries.getOrNull(fieldTypes.getOrElse(i) { -1 }) ?: return@forEachIndexed
            val value = valueFor(field, card, autofillTypes.getOrElse(i) { View.AUTOFILL_TYPE_TEXT },
                maxLengths.getOrElse(i) { -1 }, intent.getStringArrayExtra(optionsKey(i))?.toList().orEmpty())
            if (value != null) {
                @Suppress("DEPRECATION")
                builder.setValue(id, value)
                filled++
            }
        }
        if (filled == 0) {
            cancel()
            return
        }
        CardRepository.log(card.id, "Autofilled at ${intent.getStringExtra(EXTRA_SOURCE) ?: "checkout"}")
        setResult(Activity.RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, builder.build()))
        finish()
    }

    private fun valueFor(field: CardField, card: Card, autofillType: Int, maxLength: Int, options: List<String>): AutofillValue? =
        when (autofillType) {
            View.AUTOFILL_TYPE_LIST -> CardFields.listIndex(field, card, options).takeIf { it >= 0 }?.let { AutofillValue.forList(it) }
            View.AUTOFILL_TYPE_DATE -> if (field == CardField.EXP_DATE) {
                val endOfMonth = YearMonth.of(card.expYear, card.expMonth).atEndOfMonth()
                AutofillValue.forDate(endOfMonth.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
            } else null
            View.AUTOFILL_TYPE_TEXT -> AutofillValue.forText(CardFields.text(field, card, maxLength))
            else -> null
        }

    private fun cancel() {
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    companion object {
        const val EXTRA_CARD_ID = "card_id"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_IDS = "autofill_ids"
        const val EXTRA_FIELD_TYPES = "field_types"
        const val EXTRA_AUTOFILL_TYPES = "autofill_types"
        const val EXTRA_MAX_LENGTHS = "max_lengths"
        fun optionsKey(index: Int) = "options_$index"
    }
}
