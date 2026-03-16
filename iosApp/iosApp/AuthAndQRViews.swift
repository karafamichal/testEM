import SwiftUI
import Foundation
import CoreImage.CIFilterBuiltins
import UIKit

struct QRCodeView: View {
    let payload: String

    var body: some View {
        if let image = QRCodeGenerator.makeImage(from: payload) {
            Image(uiImage: image)
                .interpolation(.none)
                .resizable()
                .scaledToFit()
        } else {
            RoundedRectangle(cornerRadius: 16)
                .fill(Color.secondary.opacity(0.15))
                .overlay(
                    VStack(spacing: 4) {
                        Text("No Token")
                        Text("Waiting for token...")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                )
        }
    }

}

struct PinSetupView: View {
    @ObservedObject var viewModel: TestEMViewModel
    @State private var pin = ""
    @State private var confirmPin = ""
    @State private var error = ""

    var body: some View {
        VStack(spacing: 16) {
            Text("Set App PIN")
                .font(.system(size: 24, weight: .bold))
            Text("Protect your app with a PIN")
                .font(.footnote)
                .foregroundStyle(.secondary)

            VStack(spacing: 12) {
                SecureField("PIN (4-8 digits)", text: $pin)
                    .keyboardType(.numberPad)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 12)
                    .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 20, style: .continuous))

                SecureField("Confirm PIN", text: $confirmPin)
                    .keyboardType(.numberPad)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 12)
                    .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 20, style: .continuous))

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
                .buttonBorderShape(.roundedRectangle(radius: 16))
            }
            .padding(16)
            .background(Color(.systemBackground), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
            .shadow(color: Color.black.opacity(0.08), radius: 10, x: 0, y: 3)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
    }
}

struct PinUnlockView: View {
    @ObservedObject var viewModel: TestEMViewModel
    @State private var pin = ""
    @State private var error = ""

    var body: some View {
        VStack(spacing: 16) {
            Text("Unlock")
                .font(.system(size: 24, weight: .bold))
            Text("Enter PIN or use biometrics")
                .font(.footnote)
                .foregroundStyle(.secondary)

            VStack(spacing: 12) {
                SecureField("PIN", text: $pin)
                    .keyboardType(.numberPad)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 12)
                    .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 20, style: .continuous))

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
                .buttonBorderShape(.roundedRectangle(radius: 16))

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
                    .buttonBorderShape(.roundedRectangle(radius: 16))
                }
            }
            .padding(16)
            .background(Color(.systemBackground), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
            .shadow(color: Color.black.opacity(0.08), radius: 10, x: 0, y: 3)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
    }
}
