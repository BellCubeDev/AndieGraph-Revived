package dev.bellcube.andiegraph;

import android.app.AlertDialog;
import android.database.Cursor;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.content.Intent;
import android.provider.OpenableColumns;

import dev.bellcube.andiegraph.model.TIGutsModel;
import dev.bellcube.andiegraph.model.TISkinModel;

public class MainActivity extends Ti8xActivity {
	private static final String TAG =
			MainActivity.class.getSimpleName();

	private static final String ROM_DIRECTORY_NAME = "roms";

	/*
	 * Chosen to be unlikely to conflict with an existing menu resource ID.
	 * A menu XML resource is also fine if the application already uses one.
	 */
	private static final int MENU_IMPORT_ROM = 0x524F4D;

	/*
	 * ROM providers do not consistently report a useful MIME type, so the
	 * picker accepts any document. The contents are validated after copying.
	 */
	private static final String[] ROM_MIME_TYPES = {
			"*/*"
	};

	/*
	 * File-provider reads can involve removable storage or cloud storage,
	 * so perform the copy outside the main/UI thread.
	 */
	private static final ExecutorService ROM_IMPORT_EXECUTOR =
			Executors.newSingleThreadExecutor();

	private String mRomFilename = "";

	private boolean mRomPromptShown;
	private boolean mImportInProgress;
	private boolean mDestroyed;

	private static final int REQUEST_IMPORT_ROM = 1001;


	@Override
	public Intent getSettingsIntent() {
		return new Intent(this, SettingsActivity.class);
	}

	@Override
	public void onStart() {
		String romFilename = getPreference(
				SettingsActivity.KEY_ROM_FILENAME,
				""
		);

		/*
		 * A stored path can disappear after an app-data restore, manual file
		 * deletion, or an upgrade from the old external-storage implementation.
		 */
		if (!TextUtils.isEmpty(romFilename)
				&& getRomModel(romFilename) == -1) {
			setPreference(
					SettingsActivity.KEY_ROM_FILENAME,
					""
			);

			romFilename = "";
		}

		if (!TextUtils.equals(romFilename, mRomFilename)) {
			mSkinModel = null;
			mGutsModel = null;
			mRomFilename = romFilename;
		}

		super.onStart();

		/*
		 * Wait until onStart() and the parent initialization have completed
		 * before presenting the dialog.
		 */
		if (TextUtils.isEmpty(mRomFilename)
				&& !mRomPromptShown
				&& !mImportInProgress) {
			mRomPromptShown = true;

			getWindow()
					.getDecorView()
					.post(this::showRomRequiredDialog);
		}
	}

	@Override
	protected void onDestroy() {
		mDestroyed = true;
		super.onDestroy();
	}

	/*
	 * Add an overflow-menu command so the user can import another ROM later.
	 *
	 * This can be replaced by an existing menu XML item if the app already
	 * defines its menu that way.
	 */
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		super.onCreateOptionsMenu(menu);

		menu.add(
						Menu.NONE,
						MENU_IMPORT_ROM,
						Menu.NONE,
						R.string.action_import_rom
				)
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);

		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == MENU_IMPORT_ROM) {
			launchRomImporter();
			return true;
		}

		return super.onOptionsItemSelected(item);
	}

	/**
	 * Opens the system document picker.
	 * This may also be called by an existing button or settings command.
	 */
	private void launchRomImporter() {
		Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
		intent.addCategory(Intent.CATEGORY_OPENABLE);
		intent.setType("*/*");

		startActivityForResult(intent, REQUEST_IMPORT_ROM);
	}

	@Override
	protected void onActivityResult(
			int requestCode,
			int resultCode,
			Intent data
	) {
		super.onActivityResult(requestCode, resultCode, data);

		if (requestCode != REQUEST_IMPORT_ROM) {
			return;
		}

		if (resultCode != RESULT_OK || data == null) {
			onRomPicked(null);
			return;
		}

		onRomPicked(data.getData());
	}

	private void showRomRequiredDialog() {
		if (mDestroyed
				|| isFinishing()
				|| !TextUtils.isEmpty(mRomFilename)) {
			return;
		}

		new AlertDialog.Builder(this)
				.setTitle(R.string.dialog_rom_required_title)
				.setMessage(R.string.dialog_rom_required_message)
				.setPositiveButton(
						R.string.action_import_rom,
						(dialog, which) -> launchRomImporter()
				)
				.setNegativeButton(
						android.R.string.cancel,
						null
				)
				.show();
	}

	private void onRomPicked(Uri uri) {
		/*
		 * A null result means that the user closed the picker without
		 * selecting a document.
		 */
		if (uri == null) {
			if (TextUtils.isEmpty(mRomFilename)) {
				showCrouton(
						getString(R.string.text_no_rom_selected),
						null,
						false
				);
			}

			return;
		}

		if (mImportInProgress) {
			return;
		}

		mImportInProgress = true;

		showCrouton(
				getString(R.string.text_importing_rom),
				null,
				true
		);

		ROM_IMPORT_EXECUTOR.execute(() -> {
			try {
				ImportedRom importedRom =
						importRomIntoPrivateStorage(uri);

				runOnUiThread(
						() -> finishRomImport(importedRom)
				);
			} catch (InvalidRomException exception) {
				Log.w(
						TAG,
						"The selected document is not a supported ROM",
						exception
				);

				runOnUiThread(
						this::showInvalidRomError
				);
			} catch (Exception exception) {
				Log.e(
						TAG,
						"Could not import the selected ROM",
						exception
				);

				runOnUiThread(
						this::showRomImportError
				);
			}
		});
	}

	/**
	 * Copies a selected document into this application's private files
	 * directory and verifies that TIGutsModel recognizes it.
	 */
	private ImportedRom importRomIntoPrivateStorage(Uri uri)
			throws IOException, InvalidRomException {

		File romRootDirectory = getRomDirectory();

		if (!romRootDirectory.exists() && !romRootDirectory.mkdirs()) {
			throw new IOException(
					"Could not create ROM directory: "
							+ romRootDirectory
			);
		}

		if (!romRootDirectory.isDirectory()) {
			throw new IOException(
					"ROM path is not a directory: "
							+ romRootDirectory
			);
		}

		String originalDisplayName = queryDisplayName(uri);
		String safeFilename = sanitizeFilename(originalDisplayName);

		/*
		 * Give each import its own directory. This lets us preserve the original
		 * filename exactly enough for ROM detection without overwriting another
		 * imported file with the same name.
		 */
		File importDirectory = new File(
				romRootDirectory,
				UUID.randomUUID().toString()
		);

		if (!importDirectory.mkdirs()) {
			throw new IOException(
					"Could not create ROM import directory: "
							+ importDirectory
			);
		}

		File destination = new File(
				importDirectory,
				safeFilename
		);

		boolean keepImport = false;

		try {
			copyUriToFile(uri, destination);

			if (!destination.isFile() || destination.length() == 0) {
				throw new IOException(
						"The selected document was empty"
				);
			}

			int model = TIGutsModel.getRomFileType(destination);

			if (model == -1) {
				throw new InvalidRomException();
			}

			keepImport = true;

			return new ImportedRom(
					destination,
					originalDisplayName
			);
		} finally {
			if (!keepImport) {
				deleteRecursively(importDirectory);
			}
		}
	}

	private static String sanitizeFilename(String displayName) {
		if (TextUtils.isEmpty(displayName)) {
			return "imported.rom";
		}

		StringBuilder result = new StringBuilder(
				Math.min(displayName.length(), 200)
		);

		for (int index = 0;
		     index < displayName.length() && result.length() < 200;
		     index++) {

			char character = displayName.charAt(index);

			/*
			 * Android's internal filesystem cannot use '/' as part of a filename.
			 * NUL is invalid as well. Replace path-like characters defensively.
			 */
			if (character == '/'
					|| character == '\\'
					|| character == '\0') {
				result.append('_');
			} else {
				result.append(character);
			}
		}

		String filename = result.toString().trim();

		/*
		 * Avoid special directory names.
		 */
		if (filename.isEmpty()
				|| filename.equals(".")
				|| filename.equals("..")) {
			return "imported.rom";
		}

		return filename;
	}

	private static void deleteRecursively(File file) {
		if (file == null || !file.exists()) {
			return;
		}

		if (file.isDirectory()) {
			File[] children = file.listFiles();

			if (children != null) {
				for (File child : children) {
					deleteRecursively(child);
				}
			}
		}

		if (!file.delete()) {
			Log.w(
					TAG,
					"Could not delete failed import path: " + file
			);
		}
	}

	private void copyUriToFile(
			Uri source,
			File destination
	) throws IOException {
		try (
				InputStream input =
						getContentResolver().openInputStream(source);
				OutputStream output =
						new FileOutputStream(destination)
		) {
			if (input == null) {
				throw new IOException(
						"The selected document could not be opened"
				);
			}

			byte[] buffer = new byte[64 * 1024];
			int bytesRead;

			while ((bytesRead = input.read(buffer)) != -1) {
				output.write(buffer, 0, bytesRead);
			}

			output.flush();
		}
	}

	private void finishRomImport(ImportedRom importedRom) {
		mImportInProgress = false;

		/*
		 * The Activity could have been destroyed while a remote provider was
		 * supplying the document.
		 */
		if (mDestroyed || isFinishing()) {
			if (!importedRom.file.delete()) {
				Log.w(
						TAG,
						"Could not remove import after Activity destruction: "
								+ importedRom.file
				);
			}

			return;
		}

		hideCrouton();

		String filename =
				importedRom.file.getAbsolutePath();

		setPreference(
				SettingsActivity.KEY_ROM_FILENAME,
				filename
		);

		/*
		 * Retained for compatibility with the old settings implementation.
		 * This value now points to the private imported copy.
		 */
		setPreference(
				SettingsActivity.KEY_FOUND_FILENAMES,
				filename
		);

		mRomFilename = filename;
		mSkinModel = null;
		mGutsModel = null;

		/*
		 * The imported file has a unique name, so it automatically receives
		 * a separate RAM-state file.
		 */
		initSkin();
		startEmulator();

		showCrouton(
				getString(R.string.text_rom_imported),
				importedRom.displayName,
				false
		);
	}

	private void showInvalidRomError() {
		mImportInProgress = false;

		if (mDestroyed || isFinishing()) {
			return;
		}

		hideCrouton();

		new AlertDialog.Builder(this)
				.setTitle(R.string.dialog_invalid_rom_title)
				.setMessage(R.string.dialog_invalid_rom_message)
				.setPositiveButton(
						R.string.action_try_again,
						(dialog, which) -> launchRomImporter()
				)
				.setNegativeButton(
						android.R.string.cancel,
						null
				)
				.show();
	}

	private void showRomImportError() {
		mImportInProgress = false;

		if (mDestroyed || isFinishing()) {
			return;
		}

		hideCrouton();

		new AlertDialog.Builder(this)
				.setTitle(R.string.dialog_rom_import_failed_title)
				.setMessage(R.string.dialog_rom_import_failed_message)
				.setPositiveButton(
						R.string.action_try_again,
						(dialog, which) -> launchRomImporter()
				)
				.setNegativeButton(
						android.R.string.cancel,
						null
				)
				.show();
	}

	private File getRomDirectory() {
		return new File(
				getFilesDir(),
				ROM_DIRECTORY_NAME
		);
	}

	/**
	 * Returns the calculator model represented by a stored path, or -1 when
	 * the path is empty, missing, or unsupported.
	 */
	private int getRomModel(String filename) {
		if (TextUtils.isEmpty(filename)) {
			return -1;
		}

		File romFile = new File(filename);

		if (!romFile.isFile()) {
			return -1;
		}

		return TIGutsModel.getRomFileType(romFile);
	}

	private int getCurrentRomModel() {
		return getRomModel(mRomFilename);
	}

	@Override
	public TISkinModel getSkinModel() {
		int model = getCurrentRomModel();

		switch (model) {
			case TIGutsModel.ATI_TI82:
				return new TISkinModel(
						96,
						64,
						R.raw.ti82_480x800,
						R.drawable.ti82_480x800
				);

			case TIGutsModel.ATI_TI83:
				return new TISkinModel(
						96,
						64,
						R.raw.ti83_480x800,
						R.drawable.ti83_480x800
				);

			case TIGutsModel.ATI_TI83P:
				return new TISkinModel(
						96,
						64,
						R.raw.ti83p_480x800,
						R.drawable.ti83p_480x800
				);

			case TIGutsModel.ATI_TI85:
				return new TISkinModel(
						128,
						64,
						R.raw.ti85_480x800,
						R.drawable.ti85_480x800
				);

			case TIGutsModel.ATI_TI86:
				return new TISkinModel(
						128,
						64,
						R.raw.ti86_480x800,
						R.drawable.ti86_480x800
				);

			default:
				/*
				 * Preserve the original fallback while the ROM picker is
				 * displayed.
				 */
				return new TISkinModel(
						128,
						64,
						R.raw.ti86_480x800,
						R.drawable.ti86_480x800
				);
		}
	}

	@Override
	public TIGutsModel getGutsModel() {
		int model = getCurrentRomModel();

		if (model == -1) {
			/*
			 * The old implementation started a filesystem scan here.
			 * The new implementation simply waits for a user import.
			 */
			return null;
		}

		String ramFilename =
				calculateRamFilename(mRomFilename);

		return new TIGutsModel(
				model,
				ramFilename,
				mRomFilename
		);
	}

	private String calculateRamFilename(String romFilename) {
		int extensionPosition =
				romFilename.lastIndexOf('.');

		String baseFilename =
				extensionPosition == -1
						? romFilename
						: romFilename.substring(
						0,
						extensionPosition
				);

		return baseFilename + ".RAM";
	}

	/**
	 * Retrieves a human-readable provider name for UI display only.
	 * It is deliberately not used as the private destination filename.
	 */
	private String queryDisplayName(Uri uri) {
		String[] projection = {
				OpenableColumns.DISPLAY_NAME
		};

		try (
				Cursor cursor = getContentResolver().query(
						uri,
						projection,
						null,
						null,
						null
				)
		) {
			if (cursor != null
					&& cursor.moveToFirst()) {
				int columnIndex = cursor.getColumnIndex(
						OpenableColumns.DISPLAY_NAME
				);

				if (columnIndex >= 0) {
					String displayName =
							cursor.getString(columnIndex);

					if (!TextUtils.isEmpty(displayName)) {
						return displayName;
					}
				}
			}
		} catch (RuntimeException exception) {
			/*
			 * A provider is not required to supply a display name. Failure to
			 * obtain one should not prevent the document from being imported.
			 */
			Log.w(
					TAG,
					"Could not retrieve ROM display name",
					exception
			);
		}

		return getString(R.string.text_selected_rom);
	}

	private static final class ImportedRom {
		final File file;
		final String displayName;

		ImportedRom(
				File file,
				String displayName
		) {
			this.file = file;
			this.displayName = displayName;
		}
	}

	private static final class InvalidRomException
			extends Exception {

		InvalidRomException() {
			super("The selected file is not a recognized ROM");
		}
	}
}
