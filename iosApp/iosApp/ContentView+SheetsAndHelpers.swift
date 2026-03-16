import SwiftUI

extension ContentView {
    var accountDialogSheet: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 12) {
                    cardContainer("Account") {
                        VStack(alignment: .leading, spacing: 8) {
                            labeled("Name", viewModel.email.isEmpty ? "testEM" : viewModel.email)
                            labeled("Email", viewModel.email)
                            labeled("SNR", viewModel.serialNumber)
                            if !viewModel.nfcUid.isEmpty {
                                labeled("NFC UID", viewModel.nfcUid)
                            }
                            if !viewModel.accountDetails.cardTypeName.isEmpty {
                                labeled("Card Type", viewModel.accountDetails.cardTypeName)
                            }
                            if !viewModel.accountDetails.organizationName.isEmpty {
                                labeled("Organization", viewModel.accountDetails.organizationName)
                            }
                            if viewModel.accountDetails.cardValidFrom > 0 || viewModel.accountDetails.cardValidTo > 0 {
                                labeled("Card Valid", "\(dateText(fromMs: viewModel.accountDetails.cardValidFrom)) - \(dateText(fromMs: viewModel.accountDetails.cardValidTo))")
                            }
                            if viewModel.accountDetails.ticketValidFrom > 0 || viewModel.accountDetails.ticketValidTo > 0 {
                                labeled("Ticket Valid", "\(dateText(fromMs: viewModel.accountDetails.ticketValidFrom)) - \(dateText(fromMs: viewModel.accountDetails.ticketValidTo))")
                            }
                            if viewModel.accountDetails.discountValidFrom > 0 || viewModel.accountDetails.discountValidTo > 0 {
                                labeled("Discount Valid", "\(dateText(fromMs: viewModel.accountDetails.discountValidFrom)) - \(dateText(fromMs: viewModel.accountDetails.discountValidTo))")
                            }
                            if let credit = viewModel.accountDetails.creditLastBalance {
                                labeled("Credit", String(format: "%.2f %@", credit, viewModel.accountDetails.currencySymbol))
                            }
                        }
                    }
                }
                .padding(16)
            }
            .navigationTitle("Account")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Close") {
                        showAccountDialog = false
                    }
                }
            }
        }
    }

    var historyScreenSheet: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 12) {
                    cardContainer("Tickets & Payments History") {
                        VStack(alignment: .leading, spacing: 10) {
                            if viewModel.historyState.isLoading {
                                ProgressView()
                            }

                            if !viewModel.historyState.errorMessage.isEmpty {
                                Text(viewModel.historyState.errorMessage)
                                    .foregroundStyle(.red)
                            }

                            if !viewModel.historyState.isLoading && viewModel.historyState.items.isEmpty {
                                Text("No history found.")
                                    .foregroundStyle(.secondary)
                            }

                            ForEach(viewModel.historyState.items) { item in
                                let isPositive = item.amountText.trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix("+")
                                let isNegative = item.amountText.trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix("-")
                                let cardColor: Color = {
                                    if item.sourceType == .transaction {
                                        return viewModel.currentAccentColor.opacity(0.18)
                                    }
                                    if isPositive {
                                        return Color.green.opacity(0.18)
                                    }
                                    if isNegative {
                                        return Color.red.opacity(0.14)
                                    }
                                    return Color.secondary.opacity(0.12)
                                }()
                                let amountColor: Color = {
                                    if isPositive {
                                        return Color(red: 0.11, green: 0.50, blue: 0.23)
                                    }
                                    if isNegative {
                                        return .red
                                    }
                                    return .primary
                                }()

                                VStack(alignment: .leading, spacing: 4) {
                                    Text(dateText(fromMs: item.timestampMs))
                                        .font(.caption2)
                                        .foregroundStyle(.secondary)

                                    HStack {
                                        Text(item.title)
                                            .font(.subheadline.weight(.semibold))
                                        Spacer()
                                        if !item.amountText.isEmpty {
                                            Text(item.amountText)
                                                .font(.subheadline.weight(.semibold))
                                                .foregroundStyle(amountColor)
                                        }
                                    }

                                    if !item.subtitle.isEmpty {
                                        Text(item.subtitle)
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                    }
                                }
                                .padding(12)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .background(cardColor, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                            }
                        }
                    }
                }
                .padding(16)
            }
            .navigationTitle("Tickets & Payments History")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Back") {
                        showHistoryScreen = false
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Refresh") {
                        Task { await viewModel.loadCardHistory() }
                    }
                    .disabled(viewModel.historyState.isLoading)
                }
            }
        }
    }

    var tokenInfoSheet: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 12) {
                    if viewModel.tokenHex.isEmpty {
                        cardContainer("Token Information") {
                            Text("Waiting for token...")
                                .foregroundStyle(.secondary)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                    } else {
                        cardContainer("Token Information") {
                            VStack(alignment: .leading, spacing: 10) {
                                Text("HEX: \(viewModel.tokenHex)")
                                    .font(.system(size: 12, design: .monospaced))
                                    .textSelection(.enabled)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                Text("B64: \(viewModel.tokenBase64)")
                                    .font(.system(size: 12, design: .monospaced))
                                    .textSelection(.enabled)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                            }
                        }
                    }
                }
                .padding(16)
            }
            .navigationTitle("Token Information")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Close") {
                        showTokenInfoDialog = false
                    }
                }
            }
        }
    }

    var fullscreenQrView: some View {
        ZStack {
            Color(.systemBackground).ignoresSafeArea()
            VStack {
                Spacer()
                ZStack {
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .fill(Color.white)
                    QRCodeView(payload: viewModel.qrPayload)
                        .padding(10)
                }
                .frame(width: 300, height: 300)
                Spacer()
                Button("Close") {
                    showFullscreenQr = false
                }
                .buttonStyle(.borderedProminent)
                .padding(.bottom, 30)
            }
            .padding()
        }
    }

    func languageRow(code: String, label: String) -> some View {
        HStack {
            Image(systemName: viewModel.languageCode == code ? "largecircle.fill.circle" : "circle")
            Text(label)
            Spacer()
        }
        .contentShape(Rectangle())
        .onTapGesture {
            viewModel.setLanguageCode(code)
        }
    }

    func lockTimeoutRow(seconds: Int, label: String) -> some View {
        HStack {
            Image(systemName: viewModel.lockTimeoutSeconds == seconds ? "largecircle.fill.circle" : "circle")
            Text(label)
            Spacer()
        }
        .contentShape(Rectangle())
        .onTapGesture {
            viewModel.setLockTimeoutSeconds(seconds)
        }
    }

    func labeled(_ title: String, _ value: String) -> some View {
        HStack {
            Text(title + ":")
                .foregroundStyle(.secondary)
            Spacer()
            Text(value.isEmpty ? "-" : value)
        }
    }

    func parseHexColor(_ raw: String) -> Color? {
        let cleaned = raw.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: "#", with: "")
        let normalized: String
        if cleaned.count == 6 {
            normalized = "FF" + cleaned
        } else if cleaned.count == 8 {
            normalized = cleaned
        } else {
            return nil
        }

        guard let value = UInt64(normalized, radix: 16) else { return nil }
        let a = Double((value & 0xFF000000) >> 24) / 255.0
        let r = Double((value & 0x00FF0000) >> 16) / 255.0
        let g = Double((value & 0x0000FF00) >> 8) / 255.0
        let b = Double(value & 0x000000FF) / 255.0
        return Color(red: r, green: g, blue: b, opacity: a)
    }

    func dateText(fromMs value: Int64) -> String {
        if value <= 0 {
            return "-"
        }
        let date = Date(timeIntervalSince1970: TimeInterval(value) / 1000.0)
        return date.formatted(date: .abbreviated, time: .shortened)
    }
}
