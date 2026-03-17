import SwiftUI

extension ContentView {
    var settingsSheet: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: dp(10)) {
                    switch settingsPage {
                    case .root:
                        settingsRootView
                    case .layout:
                        layoutSettingsView
                    case .security:
                        securitySettingsView
                    }
                }
                .padding(dp(12))
            }
            .navigationTitle(settingsPageTitle)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Back") {
                        if settingsPage == .root {
                            showSettings = false
                        } else {
                            settingsPage = .root
                        }
                    }
                }
            }
            .background(viewModel.amoledEnabled ? Color.black : Color(.systemGroupedBackground))
        }
    }

    var settingsPageTitle: LocalizedStringKey {
        switch settingsPage {
        case .root: return "Settings"
        case .layout: return "Layout"
        case .security: return "Security"
        }
    }

    var settingsRootView: some View {
        VStack(spacing: dp(10)) {
            cardContainer("Theme presets") {
                VStack(alignment: .leading, spacing: 8) {
                    ForEach(viewModel.themePresets) { preset in
                        HStack {
                            Image(systemName: viewModel.selectedThemeId == preset.id ? "largecircle.fill.circle" : "circle")
                            Text(preset.name)
                            Spacer()
                            HStack(spacing: 6) {
                                Circle().fill(preset.primary).frame(width: 12, height: 12)
                                Circle().fill(preset.secondary).frame(width: 12, height: 12)
                                Circle().fill(preset.tertiary).frame(width: 12, height: 12)
                            }
                        }
                        .contentShape(Rectangle())
                        .onTapGesture {
                            viewModel.selectThemePreset(preset.id)
                        }
                    }

                    Toggle("AMOLED mode", isOn: Binding(
                        get: { viewModel.amoledEnabled },
                        set: { viewModel.setAmoledEnabled($0) }
                    ))
                }
            }

            cardContainer("Create preset") {
                VStack(alignment: .leading, spacing: 10) {
                    TextField("Preset name", text: $presetName)
                        .padding(.horizontal, dp(10))
                        .padding(.vertical, dp(8))
                        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: dp(12)))

                    TextField("Primary hex (RRGGBB or AARRGGBB)", text: $primaryHex)
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                        .padding(.horizontal, dp(10))
                        .padding(.vertical, dp(8))
                        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: dp(12)))

                    TextField("Secondary hex (RRGGBB or AARRGGBB)", text: $secondaryHex)
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                        .padding(.horizontal, dp(10))
                        .padding(.vertical, dp(8))
                        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: dp(12)))

                    TextField("Tertiary hex (RRGGBB or AARRGGBB)", text: $tertiaryHex)
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                        .padding(.horizontal, dp(10))
                        .padding(.vertical, dp(8))
                        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: dp(12)))

                    Button("Save preset") {
                        guard
                            let primary = parseHexColor(primaryHex),
                            let secondary = parseHexColor(secondaryHex),
                            let tertiary = parseHexColor(tertiaryHex)
                        else {
                            return
                        }
                        viewModel.addThemePreset(
                            name: presetName,
                            primary: primary,
                            secondary: secondary,
                            tertiary: tertiary
                        )
                        presetName = ""
                        primaryHex = ""
                        secondaryHex = ""
                        tertiaryHex = ""
                    }
                    .frame(maxWidth: .infinity)
                    .buttonStyle(.borderedProminent)
                    .disabled(
                        presetName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
                        parseHexColor(primaryHex) == nil ||
                        parseHexColor(secondaryHex) == nil ||
                        parseHexColor(tertiaryHex) == nil
                    )
                }
            }

            cardContainer("Language") {
                VStack(alignment: .leading, spacing: 8) {
                    languageRow(code: "sk", label: "Slovak (preferred)")
                    languageRow(code: "en", label: "English")
                }
            }

            cardContainer("Layout") {
                Button("Open Layout Settings") {
                    settingsPage = .layout
                }
                .frame(maxWidth: .infinity)
                .buttonStyle(.borderedProminent)
            }

            cardContainer("Security") {
                Button("Open Security Settings") {
                    settingsPage = .security
                }
                .frame(maxWidth: .infinity)
                .buttonStyle(.borderedProminent)
            }

            cardContainer("Account") {
                VStack(spacing: 10) {
                    Button("Logout", role: .destructive) {
                        showLogoutConfirm = true
                    }
                    .frame(maxWidth: .infinity)
                    .buttonStyle(.borderedProminent)
                }
            }
        }
    }

    var layoutSettingsView: some View {
        VStack(spacing: dp(10)) {
            ForEach(Array(viewModel.layoutOrder.enumerated()), id: \.element.rawValue) { index, id in
                cardContainer(sectionTitle(id)) {
                    HStack(spacing: 8) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(sectionTitle(id))
                                .fontWeight(.medium)
                            if hideableSections.contains(id) {
                                Text(viewModel.hiddenSections.contains(id) ? "Hidden" : "Visible")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            } else {
                                Text("Always visible")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                        Spacer()
                        if hideableSections.contains(id) {
                            Button(viewModel.hiddenSections.contains(id) ? "Show" : "Hide") {
                                viewModel.setSectionHidden(id, hidden: !viewModel.hiddenSections.contains(id))
                            }
                        }
                        Button("Up") {
                            viewModel.moveLayoutItem(id, direction: -1)
                        }
                        .disabled(index == 0)
                        Button("Down") {
                            viewModel.moveLayoutItem(id, direction: 1)
                        }
                        .disabled(index == viewModel.layoutOrder.count - 1)
                    }
                }
            }
        }
    }

    var securitySettingsView: some View {
        VStack(spacing: dp(10)) {
            cardContainer("Biometrics") {
                Toggle("Biometrics", isOn: Binding(
                    get: { viewModel.biometricEnabled },
                    set: { viewModel.setBiometricEnabled($0) }
                ))
            }

            cardContainer("Lock timeout") {
                VStack(alignment: .leading, spacing: 6) {
                    lockTimeoutRow(seconds: 0, label: "Immediately")
                    lockTimeoutRow(seconds: 30, label: "After 30 seconds")
                    lockTimeoutRow(seconds: 60, label: "After 1 minute")
                    lockTimeoutRow(seconds: 300, label: "After 5 minutes")

                    TextField("Custom seconds", text: $customTimeout)
                        .keyboardType(.numberPad)
                        .padding(.horizontal, dp(10))
                        .padding(.vertical, dp(8))
                        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: dp(12)))
                        .onChange(of: customTimeout) { _, value in
                            let digits = value.filter { $0.isNumber }
                            if digits != value {
                                customTimeout = digits
                            }
                            if let seconds = Int(digits) {
                                viewModel.setLockTimeoutSeconds(seconds)
                            }
                        }
                }
            }

            cardContainer("PIN") {
                Button("Change PIN") {
                    changePinCurrent = ""
                    changePinNew = ""
                    changePinConfirm = ""
                    changePinError = ""
                    showChangePinSheet = true
                }
                .frame(maxWidth: .infinity)
                .buttonStyle(.borderedProminent)
            }
        }
    }

    var changePinSheet: some View {
        NavigationStack {
            Form {
                Section("Current") {
                    SecureField("Current PIN", text: $changePinCurrent)
                        .keyboardType(.numberPad)
                }
                Section("New") {
                    SecureField("New PIN (4-8 digits)", text: $changePinNew)
                        .keyboardType(.numberPad)
                    SecureField("Confirm New PIN", text: $changePinConfirm)
                        .keyboardType(.numberPad)
                }
                if !changePinError.isEmpty {
                    Section("Error") {
                        Text(changePinError).foregroundStyle(.red)
                    }
                }
                Section {
                    Button("Update") {
                        if changePinCurrent.count < 4 {
                            changePinError = "Enter current PIN."
                            return
                        }
                        if changePinNew.count < 4 {
                            changePinError = "New PIN is too short."
                            return
                        }
                        if changePinNew != changePinConfirm {
                            changePinError = "PIN confirmation does not match."
                            return
                        }
                        if viewModel.changePin(currentPin: changePinCurrent, newPin: changePinNew) {
                            showChangePinSheet = false
                        } else {
                            changePinError = "Current PIN is incorrect."
                        }
                    }
                }
            }
            .navigationTitle("Change PIN")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Close") {
                        showChangePinSheet = false
                    }
                }
            }
        }
    }

    func sectionTitle(_ id: SectionId) -> LocalizedStringKey {
        switch id {
        case .status: return "Polling status"
        case .qr: return "QR code"
        case .nfc: return "NFC button"
        case .controls: return "Controls"
        case .error: return "Errors"
        }
    }
}
