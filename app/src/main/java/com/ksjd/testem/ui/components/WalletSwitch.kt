package com.ksjd.testem.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ksjd.testem.R
import com.ksjd.testem.Wallet

/**
 * The selector at the top of Home, History and Account when both accounts are saved (EM-0023):
 * SAD Zvolen | IDS BBSK, with "Both" first where the two can be merged (History).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletSwitch(selected: Wallet?, onSelect: (Wallet?) -> Unit, withBoth: Boolean = false, modifier: Modifier = Modifier) {
    val options: List<Wallet?> = if (withBoth) listOf(null, Wallet.SADZV, Wallet.IDS) else listOf(Wallet.SADZV, Wallet.IDS)
    val view = LocalView.current
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        options.forEachIndexed { index, wallet ->
            SegmentedButton(
                selected = selected == wallet,
                onClick = { if (selected != wallet) Haptics.perform(view); onSelect(wallet) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                label = {
                    Text(stringResource(when (wallet) {
                        null -> R.string.wallet_both
                        Wallet.SADZV -> R.string.wallet_sadzv
                        Wallet.IDS -> R.string.wallet_ids
                    }), maxLines = 1)
                }
            )
        }
    }
}
