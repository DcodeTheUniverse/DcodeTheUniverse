package com.dcode.wallet.autofill

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.util.Log
import android.widget.RemoteViews
import com.dcode.wallet.R
import com.dcode.wallet.data.Card
import com.dcode.wallet.data.CardRepository
import java.util.concurrent.atomic.AtomicInteger

/**
 * Offers saved cards on checkout forms in other apps and browsers. Each suggestion is locked:
 * card details are only released after fingerprint / screen-lock verification in [AutofillAuthActivity].
 */
class CardAutofillService : AutofillService() {

    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        val response = try {
            buildResponse(request)
        } catch (e: Exception) {
            Log.e(TAG, "Fill request failed", e)
            null
        }
        callback.onSuccess(response)
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) = callback.onSuccess()

    private fun buildResponse(request: FillRequest): FillResponse? {
        val structure = request.fillContexts.lastOrNull()?.structure ?: return null
        if (structure.activityComponent.packageName == packageName) return null
        val form = StructureParser.parse(structure)
        if (!form.isPaymentForm) return null

        CardRepository.init(this)
        val cards = CardRepository.state.value.cardsDefaultFirst.filterNot { it.isExpired() }
        if (cards.isEmpty()) return null

        val source = form.webDomain ?: appLabel(form.packageName)
        val builder = FillResponse.Builder()
        cards.forEach { card ->
            val presentation = presentation(card)
            @Suppress("DEPRECATION")
            val dataset = Dataset.Builder(presentation).apply {
                // Values stay null until the user authenticates.
                form.fields.forEach { setValue(it.id, null) }
                setAuthentication(authIntent(card, form, source))
            }.build()
            builder.addDataset(dataset)
        }
        return builder.build()
    }

    private fun presentation(card: Card) = RemoteViews(packageName, R.layout.autofill_item).apply {
        setTextViewText(R.id.title, "${card.title} •••• ${card.last4}")
        setTextViewText(R.id.subtitle, "Dcode Wallet · unlock to fill")
    }

    private fun authIntent(card: Card, form: ParsedForm, source: String) =
        Intent(this, AutofillAuthActivity::class.java).apply {
            putExtra(AutofillAuthActivity.EXTRA_CARD_ID, card.id)
            putExtra(AutofillAuthActivity.EXTRA_SOURCE, source)
            putParcelableArrayListExtra(AutofillAuthActivity.EXTRA_IDS, ArrayList(form.fields.map { it.id }))
            putExtra(AutofillAuthActivity.EXTRA_FIELD_TYPES, form.fields.map { it.type.ordinal }.toIntArray())
            putExtra(AutofillAuthActivity.EXTRA_AUTOFILL_TYPES, form.fields.map { it.autofillType }.toIntArray())
            putExtra(AutofillAuthActivity.EXTRA_MAX_LENGTHS, form.fields.map { it.maxLength }.toIntArray())
            form.fields.forEachIndexed { i, f ->
                if (f.options.isNotEmpty()) putExtra(AutofillAuthActivity.optionsKey(i), f.options.toTypedArray())
            }
        }.let { intent ->
            val flags = PendingIntent.FLAG_CANCEL_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            PendingIntent.getActivity(this, requestCodes.incrementAndGet(), intent, flags).intentSender
        }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }

    companion object {
        private const val TAG = "CardAutofillService"
        private val requestCodes = AtomicInteger(0)
    }
}
