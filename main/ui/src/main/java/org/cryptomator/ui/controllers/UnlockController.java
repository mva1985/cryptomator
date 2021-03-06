/*******************************************************************************
 * Copyright (c) 2014 Sebastian Stenzel
 * This file is licensed under the terms of the MIT license.
 * See the LICENSE.txt file for more info.
 * 
 * Contributors:
 *     Sebastian Stenzel - initial API and implementation
 ******************************************************************************/
package org.cryptomator.ui.controllers;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ResourceBundle;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.value.ObservableValue;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyEvent;
import javafx.scene.text.Text;

import javax.security.auth.DestroyFailedException;

import org.apache.commons.lang3.CharUtils;
import org.cryptomator.crypto.exceptions.DecryptFailedException;
import org.cryptomator.crypto.exceptions.UnsupportedKeyLengthException;
import org.cryptomator.crypto.exceptions.UnsupportedVaultException;
import org.cryptomator.crypto.exceptions.WrongPasswordException;
import org.cryptomator.ui.controls.SecPasswordField;
import org.cryptomator.ui.model.Vault;
import org.cryptomator.ui.util.FXThreads;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;

public class UnlockController implements Initializable {

	private static final Logger LOG = LoggerFactory.getLogger(UnlockController.class);

	private ResourceBundle rb;
	private UnlockListener listener;
	private Vault vault;

	@FXML
	private SecPasswordField passwordField;

	@FXML
	private TextField mountName;

	@FXML
	private Button unlockButton;

	@FXML
	private ProgressIndicator progressIndicator;

	@FXML
	private Text messageText;

	@FXML
	private Hyperlink downloadsPageLink;

	private final ExecutorService exec;
	private final Application app;

	@Inject
	public UnlockController(Application app, ExecutorService exec) {
		super();
		this.app = app;
		this.exec = exec;
	}

	@Override
	public void initialize(URL url, ResourceBundle rb) {
		this.rb = rb;

		passwordField.textProperty().addListener(this::passwordFieldsDidChange);
		mountName.addEventFilter(KeyEvent.KEY_TYPED, this::filterAlphanumericKeyEvents);
		mountName.textProperty().addListener(this::mountNameDidChange);
	}

	// ****************************************
	// Password field
	// ****************************************

	private void passwordFieldsDidChange(ObservableValue<? extends String> property, String oldValue, String newValue) {
		boolean passwordIsEmpty = passwordField.getText().isEmpty();
		unlockButton.setDisable(passwordIsEmpty);
	}

	// ****************************************
	// Downloads link
	// ****************************************

	@FXML
	public void didClickDownloadsLink(ActionEvent event) {
		app.getHostServices().showDocument("https://cryptomator.org/downloads/");
	}

	// ****************************************
	// Unlock button
	// ****************************************

	@FXML
	private void didClickUnlockButton(ActionEvent event) {
		setControlsDisabled(true);
		progressIndicator.setVisible(true);
		downloadsPageLink.setVisible(false);
		final Path masterKeyPath = vault.getPath().resolve(Vault.VAULT_MASTERKEY_FILE);
		final Path masterKeyBackupPath = vault.getPath().resolve(Vault.VAULT_MASTERKEY_BACKUP_FILE);
		final CharSequence password = passwordField.getCharacters();
		try (final InputStream masterKeyInputStream = Files.newInputStream(masterKeyPath, StandardOpenOption.READ)) {
			vault.getCryptor().decryptMasterKey(masterKeyInputStream, password);
			if (!vault.startServer()) {
				messageText.setText(rb.getString("unlock.messageLabel.startServerFailed"));
				vault.getCryptor().destroy();
				return;
			}
			// at this point we know for sure, that the masterkey can be decrypted, so lets make a backup:
			Files.copy(masterKeyPath, masterKeyBackupPath, StandardCopyOption.REPLACE_EXISTING);
			vault.setUnlocked(true);
			final Future<Boolean> futureMount = exec.submit(() -> vault.mount());
			FXThreads.runOnMainThreadWhenFinished(exec, futureMount, this::unlockAndMountFinished);
		} catch (DecryptFailedException | IOException ex) {
			setControlsDisabled(false);
			progressIndicator.setVisible(false);
			messageText.setText(rb.getString("unlock.errorMessage.decryptionFailed"));
			LOG.error("Decryption failed for technical reasons.", ex);
		} catch (WrongPasswordException e) {
			setControlsDisabled(false);
			progressIndicator.setVisible(false);
			messageText.setText(rb.getString("unlock.errorMessage.wrongPassword"));
			Platform.runLater(passwordField::requestFocus);
		} catch (UnsupportedKeyLengthException ex) {
			setControlsDisabled(false);
			progressIndicator.setVisible(false);
			messageText.setText(rb.getString("unlock.errorMessage.unsupportedKeyLengthInstallJCE"));
			LOG.warn("Unsupported Key-Length. Please install Oracle Java Cryptography Extension (JCE).", ex);
		} catch (UnsupportedVaultException e) {
			setControlsDisabled(false);
			progressIndicator.setVisible(false);
			downloadsPageLink.setVisible(true);
			if (e.isVaultOlderThanSoftware()) {
				messageText.setText(rb.getString("unlock.errorMessage.unsupportedVersion.vaultOlderThanSoftware") + " ");
			} else if (e.isSoftwareOlderThanVault()) {
				messageText.setText(rb.getString("unlock.errorMessage.unsupportedVersion.softwareOlderThanVault") + " ");
			}
		} catch (DestroyFailedException e) {
			setControlsDisabled(false);
			progressIndicator.setVisible(false);
			LOG.error("Destruction of cryptor threw an exception.", e);
		} finally {
			passwordField.swipe();
		}
	}

	private void setControlsDisabled(boolean disable) {
		passwordField.setDisable(disable);
		mountName.setDisable(disable);
		unlockButton.setDisable(disable);
	}

	private void unlockAndMountFinished(boolean mountSuccess) {
		progressIndicator.setVisible(false);
		setControlsDisabled(false);
		if (vault.isUnlocked() && !mountSuccess) {
			vault.stopServer();
		}
		if (mountSuccess && listener != null) {
			listener.didUnlock(this);
		}
	}

	public void filterAlphanumericKeyEvents(KeyEvent t) {
		if (t.getCharacter() == null || t.getCharacter().length() == 0) {
			return;
		}
		char c = t.getCharacter().charAt(0);
		if (!CharUtils.isAsciiAlphanumeric(c)) {
			t.consume();
		}
	}

	private void mountNameDidChange(ObservableValue<? extends String> property, String oldValue, String newValue) {
		// newValue is guaranteed to be a-z0-9, see #filterAlphanumericKeyEvents
		if (newValue.isEmpty()) {
			mountName.setText(vault.getMountName());
		} else {
			vault.setMountName(newValue);
		}
	}

	/* Getter/Setter */

	public Vault getVault() {
		return vault;
	}

	public void setVault(Vault vault) {
		this.vault = vault;
		this.mountName.setText(vault.getMountName());
	}

	public UnlockListener getListener() {
		return listener;
	}

	public void setListener(UnlockListener listener) {
		this.listener = listener;
	}

	/* callback */

	interface UnlockListener {
		void didUnlock(UnlockController ctrl);
	}

}
