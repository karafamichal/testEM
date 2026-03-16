import SwiftUI

extension ContentView {
    var loginView: some View {
        GeometryReader { geometry in
            ScrollView {
                VStack(spacing: dp(12)) {
                    VStack(spacing: 4) {
                        Text("testEM")
                            .font(.system(size: 30 * uiScale, weight: .bold))
                        Text("Real-time QR Token Generator")
                            .font(.system(size: 13 * uiScale))
                            .foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity, alignment: .center)

                    cardContainer("Credentials") {
                        VStack(spacing: dp(10)) {
                            TextField("Email or Username", text: $viewModel.email)
                                .textInputAutocapitalization(.never)
                                .autocorrectionDisabled()
                                .padding(.horizontal, dp(10))
                                .padding(.vertical, dp(8))
                                .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: dp(18), style: .continuous))

                            SecureField("Password", text: $viewModel.password)
                                .padding(.horizontal, dp(10))
                                .padding(.vertical, dp(8))
                                .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: dp(18), style: .continuous))
                        }
                    }

                    Button {
                        viewModel.login()
                    } label: {
                        HStack {
                            if viewModel.isLoggingIn {
                                ProgressView()
                                    .tint(.white)
                            }
                            Text(viewModel.isLoggingIn ? "Logging in..." : "Login")
                                .fontWeight(.semibold)
                        }
                        .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .buttonBorderShape(.roundedRectangle(radius: dp(16)))
                    .disabled(viewModel.isLoggingIn)
                    .frame(height: dp(48))
                }
                .padding(.horizontal, dp(6))
                .frame(maxWidth: 520)
                .frame(maxWidth: .infinity)
                .frame(minHeight: geometry.size.height)
                .frame(maxHeight: .infinity, alignment: .center)
            }
        }
    }

    var daemonView: some View {
        ScrollView {
            VStack(spacing: dp(12)) {
                ForEach(viewModel.layoutOrder, id: \.rawValue) { id in
                    if !viewModel.hiddenSections.contains(id) || !hideableSections.contains(id) {
                        sectionView(for: id)
                    }
                }
            }
            .frame(maxWidth: .infinity)
        }
    }

    var hideableSections: Set<SectionId> {
        [.status, .nfc, .error]
    }

    @ViewBuilder
    func sectionView(for id: SectionId) -> some View {
        switch id {
        case .status:
            cardContainer("Polling status") {
                VStack(alignment: .leading, spacing: dp(6)) {
                    HStack {
                        Text("Status:")
                            .fontWeight(.bold)
                        Spacer()
                        Text(viewModel.isPolling ? "Polling Active" : "Polling Paused")
                            .font(.caption2.weight(.bold))
                            .padding(.horizontal, dp(7))
                            .padding(.vertical, dp(3))
                            .background(viewModel.isPolling ? Color.green.opacity(0.2) : Color.gray.opacity(0.2), in: Capsule())
                    }
                    Text("Last Update: \(viewModel.lastUpdated?.formatted(date: .omitted, time: .standard) ?? "Never")")
                        .font(.system(size: 12 * uiScale))
                        .foregroundStyle(.secondary)
                    if !viewModel.statusMessage.isEmpty {
                        Text(viewModel.statusMessage)
                            .font(.system(size: 12 * uiScale))
                            .foregroundStyle(.secondary)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        case .qr:
            cardContainer("Current QR Code") {
                VStack(spacing: dp(10)) {
                    HStack {
                        Spacer()
                        Button {
                            showTokenInfoDialog = true
                        } label: {
                            Image(systemName: "info.circle.fill")
                                .font(.caption)
                                .padding(dp(6))
                                .background(Color(.secondarySystemBackground), in: Circle())
                        }
                        .buttonStyle(.plain)
                    }

                    QRCodeView(payload: viewModel.qrPayload)
                        .frame(width: dp(220), height: dp(220))
                        .onTapGesture {
                            showFullscreenQr = true
                        }
                    if !viewModel.tokenHex.isEmpty {
                        Text(viewModel.tokenHex)
                            .font(.system(size: 12, design: .monospaced))
                            .textSelection(.enabled)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .frame(maxWidth: .infinity)
            }
        case .nfc:
            cardContainer("NFC") {
                VStack(spacing: dp(8)) {
                    Button(viewModel.nfcEnabled ? "Switch to QR" : "Switch to NFC") {
                        let uid = viewModel.toggleNfc()
                        if let uid {
                            nfcUidToShow = uid
                            showNfcUidDialog = true
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .buttonStyle(.bordered)
                    .buttonBorderShape(.roundedRectangle(radius: dp(12)))
                    .disabled(viewModel.qrPayload.isEmpty)

                    if viewModel.nfcEnabled && !viewModel.nfcUid.isEmpty {
                        Text("UID: \(viewModel.nfcUid)")
                            .font(.caption)
                            .textSelection(.enabled)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
            }
        case .controls:
            cardContainer("Controls") {
                VStack(spacing: dp(8)) {
                    Button(viewModel.isPolling ? "Stop" : "Start") {
                        if viewModel.isPolling {
                            viewModel.stopPolling()
                        } else {
                            viewModel.startPolling()
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .frame(height: dp(48))
                    .tint(viewModel.isPolling ? .red : viewModel.currentAccentColor)
                    .buttonStyle(.borderedProminent)
                    .buttonBorderShape(.roundedRectangle(radius: dp(16)))

                    Button("Ticket & Payment History") {
                        showHistoryScreen = true
                    }
                    .frame(maxWidth: .infinity)
                    .frame(height: dp(48))
                    .buttonStyle(.bordered)
                    .buttonBorderShape(.roundedRectangle(radius: dp(16)))
                    .disabled(viewModel.qrPayload.isEmpty)
                }
            }
        case .error:
            if !viewModel.errorMessage.isEmpty {
                cardContainer("Errors") {
                    Text(viewModel.errorMessage)
                        .foregroundStyle(.red)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }
    }

    func cardContainer<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: dp(8)) {
            Text(title)
                .font(.system(size: 16 * uiScale, weight: .semibold))
            content()
        }
        .padding(dp(12))
        .frame(maxWidth: .infinity)
        .background(viewModel.amoledEnabled ? Color(.secondarySystemBackground) : Color(.systemBackground))
        .clipShape(RoundedRectangle(cornerRadius: dp(16), style: .continuous))
        .shadow(color: Color.black.opacity(viewModel.amoledEnabled ? 0.0 : 0.08), radius: dp(8), x: 0, y: 2)
    }
}
