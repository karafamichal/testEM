import SwiftUI

extension ContentView {
    var loginView: some View {
        ScrollView {
            VStack(spacing: 16) {
                VStack(spacing: 4) {
                    Text("testEM")
                        .font(.largeTitle.weight(.bold))
                    Text("Real-time QR Token Generator")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, alignment: .center)

                cardContainer("Credentials") {
                    VStack(spacing: 12) {
                        TextField("Email or Username", text: $viewModel.email)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .padding(.horizontal, 12)
                            .padding(.vertical, 10)
                            .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 24, style: .continuous))

                        SecureField("Password", text: $viewModel.password)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 10)
                            .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 24, style: .continuous))
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
                .buttonBorderShape(.roundedRectangle(radius: 18))
                .disabled(viewModel.isLoggingIn)
                .frame(height: 56)

                cardContainer("Status") {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(viewModel.statusMessage)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        if !viewModel.errorMessage.isEmpty {
                            Text(viewModel.errorMessage)
                                .foregroundStyle(.red)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                    }
                }

            }
            .padding(.horizontal, 8)
        }
    }

    var daemonView: some View {
        ScrollView {
            VStack(spacing: 16) {
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
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Text("Status:")
                            .fontWeight(.bold)
                        Spacer()
                        Text(viewModel.isPolling ? "Polling Active" : "Polling Paused")
                            .font(.caption2.weight(.bold))
                            .padding(.horizontal, 8)
                            .padding(.vertical, 4)
                            .background(viewModel.isPolling ? Color.green.opacity(0.2) : Color.gray.opacity(0.2), in: Capsule())
                    }
                    Text("Last Update: \(viewModel.lastUpdated?.formatted(date: .omitted, time: .standard) ?? "Never")")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    if !viewModel.statusMessage.isEmpty {
                        Text(viewModel.statusMessage)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        case .qr:
            cardContainer("Current QR Code") {
                VStack(spacing: 12) {
                    HStack {
                        Spacer()
                        Button {
                            showTokenInfoDialog = true
                        } label: {
                            Image(systemName: "info.circle.fill")
                                .font(.caption)
                                .padding(8)
                                .background(Color(.secondarySystemBackground), in: Circle())
                        }
                        .buttonStyle(.plain)
                    }

                    QRCodeView(payload: viewModel.qrPayload)
                        .frame(width: 260, height: 260)
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
                VStack(spacing: 10) {
                    Button(viewModel.nfcEnabled ? "Switch to QR" : "Switch to NFC") {
                        let uid = viewModel.toggleNfc()
                        if let uid {
                            nfcUidToShow = uid
                            showNfcUidDialog = true
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .buttonStyle(.bordered)
                    .buttonBorderShape(.roundedRectangle(radius: 14))
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
                VStack(spacing: 10) {
                    Button(viewModel.isPolling ? "Stop" : "Get QR") {
                        if viewModel.isPolling {
                            viewModel.stopPolling()
                        } else {
                            viewModel.startPolling()
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .frame(height: 56)
                    .tint(viewModel.isPolling ? .red : viewModel.currentAccentColor)
                    .buttonStyle(.borderedProminent)
                    .buttonBorderShape(.roundedRectangle(radius: 18))

                    Button("Ticket & Payment History") {
                        showHistoryScreen = true
                    }
                    .frame(maxWidth: .infinity)
                    .frame(height: 56)
                    .buttonStyle(.bordered)
                    .buttonBorderShape(.roundedRectangle(radius: 18))
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
        VStack(alignment: .leading, spacing: 10) {
            Text(title)
                .font(.headline)
            content()
        }
        .padding(16)
        .frame(maxWidth: .infinity)
        .background(viewModel.amoledEnabled ? Color(.secondarySystemBackground) : Color(.systemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .shadow(color: Color.black.opacity(viewModel.amoledEnabled ? 0.0 : 0.08), radius: 10, x: 0, y: 3)
    }
}
