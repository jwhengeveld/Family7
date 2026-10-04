import SwiftUI

struct LoginView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openURL) private var openURL
    @State private var email = ""
    @State private var password = ""
    @FocusState private var focused: Field?

    private enum Field { case email, password }

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                Image("Family7Logo").resizable().scaledToFit().frame(height: 64).padding(.top, 48)
                Text("Log in met uw Family7 Plus-account")
                    .foregroundStyle(Color.family7Secondary)

                VStack(spacing: 12) {
                    TextField("E-mailadres", text: $email)
                        .textContentType(.username)
                        .keyboardType(.emailAddress)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .focused($focused, equals: .email)
                        .submitLabel(.next)
                        .onSubmit { focused = .password }
                    SecureField("Wachtwoord", text: $password)
                        .textContentType(.password)
                        .focused($focused, equals: .password)
                        .submitLabel(.go)
                        .onSubmit(submit)
                }
                .textFieldStyle(Family7FieldStyle())

                if let error = model.loginError {
                    Text(error).font(.footnote).foregroundStyle(.red).frame(maxWidth: .infinity, alignment: .leading)
                }

                Button(action: submit) {
                    Group {
                        if model.isLoggingIn { ProgressView().tint(.white) } else { Text("Inloggen").bold() }
                    }
                    .frame(maxWidth: .infinity, minHeight: 32)
                }
                .buttonStyle(.borderedProminent)
                .tint(.family7Red)
                .disabled(model.isLoggingIn || email.isEmpty || password.isEmpty)

                Button("Wachtwoord vergeten?") { openURL(URL(string: "https://www.family7.nl/user/password")!) }
                    .font(.footnote)

                Text("Nog geen account? Family7 Plus kost € 3 per maand; de eerste 10 dagen zijn gratis.")
                    .font(.footnote)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(Color.family7Secondary)
                    .padding(.top, 16)
                Button("Account aanmaken") { openURL(URL(string: "https://www.family7.nl/plus/user/register")!) }
            }
            .frame(maxWidth: 420)
            .padding(24)
            .frame(maxWidth: .infinity)
        }
        .background(LinearGradient(colors: [.family7Blue, .family7Background], startPoint: .top, endPoint: .bottom).ignoresSafeArea())
        .scrollDismissesKeyboard(.interactively)
    }

    private func submit() {
        guard !email.isEmpty, !password.isEmpty else { return }
        focused = nil
        model.login(email: email, password: password)
    }
}

private struct Family7FieldStyle: TextFieldStyle {
    func _body(configuration: TextField<Self._Label>) -> some View {
        configuration
            .padding(14)
            .background(Color.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 10))
            .overlay(RoundedRectangle(cornerRadius: 10).stroke(Color.white.opacity(0.2)))
    }
}
