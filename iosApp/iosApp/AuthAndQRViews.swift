import SwiftUI
import Foundation
import CoreImage.CIFilterBuiltins
import UIKit

private let compactScale: CGFloat = min(max(UIScreen.main.bounds.width / 390.0, 0.84), 1.0)

private func s(_ value: CGFloat) -> CGFloat {
    value * compactScale
}

struct QRCodeView: View {
    let payload: String

    var body: some View {
        let trimmedPayload = payload.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmedPayload.isEmpty, let image = QRCodeGenerator.makeImage(from: trimmedPayload) {
            Image(uiImage: image)
                .interpolation(.none)
                .resizable()
                .scaledToFit()
        } else {
            Text("Waiting for token...")
                .font(.system(size: 13 * compactScale))
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
        }
    }

}

struct PinSetupView: View {
    @ObservedObject var viewModel: TestEMViewModel
    @State private var pin = ""
    @State private var confirmPin = ""
    @State private var error = ""

    var body: some View {
        VStack(spacing: s(12)) {
            Text("Set App PIN")
                .font(.system(size: 22 * compactScale, weight: .bold))
            Text("Protect your app with a PIN")
                .font(.system(size: 12 * compactScale))
                .foregroundStyle(.secondary)

            VStack(spacing: s(10)) {
                SecureField("PIN (4-8 digits)", text: $pin)
                    .keyboardType(.numberPad)
                    .padding(.horizontal, s(12))
                    .padding(.vertical, s(10))
                    .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: s(16), style: .continuous))

                SecureField("Confirm PIN", text: $confirmPin)
                    .keyboardType(.numberPad)
                    .padding(.horizontal, s(12))
                    .padding(.vertical, s(10))
                    .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: s(16), style: .continuous))

                if !error.isEmpty {
                    Text(error)
                        .font(.footnote)
                        .foregroundStyle(.red)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }

                Button("Save PIN") {
                    guard pin == confirmPin else {
                        error = "PINs do not match."
                        return
                    }
                    if viewModel.setPin(pin) {
                        pin = ""
                        confirmPin = ""
                        error = ""
                    } else {
                        error = "PIN must be 4-8 digits."
                    }
                }
                .frame(maxWidth: .infinity)
                .buttonStyle(.borderedProminent)
                .buttonBorderShape(.roundedRectangle(radius: s(14)))
            }
            .padding(s(12))
            .background(Color(.systemBackground), in: RoundedRectangle(cornerRadius: s(16), style: .continuous))
            .shadow(color: Color.black.opacity(0.08), radius: s(8), x: 0, y: 2)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
    }
}

struct PinUnlockView: View {
    @ObservedObject var viewModel: TestEMViewModel
    @State private var pin = ""
    @State private var error = ""

    var body: some View {
        VStack(spacing: s(12)) {
            Text("Unlock")
                .font(.system(size: 22 * compactScale, weight: .bold))
            Text("Enter PIN or use biometrics")
                .font(.system(size: 12 * compactScale))
                .foregroundStyle(.secondary)

            VStack(spacing: s(10)) {
                SecureField("PIN", text: $pin)
                    .keyboardType(.numberPad)
                    .padding(.horizontal, s(12))
                    .padding(.vertical, s(10))
                    .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: s(16), style: .continuous))

                if !error.isEmpty {
                    Text(error)
                        .font(.footnote)
                        .foregroundStyle(.red)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }

                Button("Unlock with PIN") {
                    if viewModel.verifyPin(pin) {
                        error = ""
                        pin = ""
                    } else {
                        error = "Incorrect PIN"
                    }
                }
                .frame(maxWidth: .infinity)
                .buttonStyle(.borderedProminent)
                .buttonBorderShape(.roundedRectangle(radius: s(14)))

                if viewModel.biometricEnabled {
                    Button("Use Face ID / Touch ID") {
                        Task {
                            let ok = await viewModel.unlockWithBiometrics()
                            if !ok {
                                error = "Biometric unlock failed"
                            } else {
                                error = ""
                            }
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .buttonStyle(.bordered)
                    .buttonBorderShape(.roundedRectangle(radius: s(14)))
                }
            }
            .padding(s(12))
            .background(Color(.systemBackground), in: RoundedRectangle(cornerRadius: s(16), style: .continuous))
            .shadow(color: Color.black.opacity(0.08), radius: s(8), x: 0, y: 2)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
    }
}
