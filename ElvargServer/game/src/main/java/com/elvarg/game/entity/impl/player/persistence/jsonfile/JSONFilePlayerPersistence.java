package com.elvarg.game.entity.impl.player.persistence.jsonfile;

import com.elvarg.Server;
import com.elvarg.game.entity.impl.player.Player;
import com.elvarg.game.entity.impl.player.persistence.PlayerPersistence;
import com.elvarg.game.entity.impl.player.persistence.PlayerSave;
import com.elvarg.util.Misc;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Level;

public class JSONFilePlayerPersistence extends PlayerPersistence {

    private static final String PATH = "../data/saves/characters/";
    private static final Gson BUILDER = new GsonBuilder().create();

    @Override
    public PlayerSave load(String username) {
        if (!exists(username)) {
            return null;
        }

        Path path = Paths.get(PATH, username + ".json");
        File file = path.toFile();

        try (FileReader fileReader = new FileReader(file)) {
            return BUILDER.fromJson(fileReader, PlayerSave.class);
        } catch (Exception e) {
            // Unreadable (e.g. truncated by a crash under the old non-atomic writer): fall back to the last good backup.
            File bak = new File(file.getPath() + ".bak");
            if (bak.exists()) {
                try (FileReader r = new FileReader(bak)) {
                    PlayerSave s = BUILDER.fromJson(r, PlayerSave.class);
                    if (s != null) {
                        System.out.println("[Persistence] " + username + ".json unreadable, loaded .bak");
                        return s;
                    }
                } catch (Exception e2) {
                    // fall through
                }
            }
            throw new RuntimeException(e);
        }
    }

    @Override
    public void save(Player player) {
        PlayerSave save = PlayerSave.fromPlayer(player);

        Path path = Paths.get(PATH, player.getUsername() + ".json");
        File file = path.toFile();
        setupDirectory(file);

        Gson builder = new GsonBuilder().setPrettyPrinting().create();

		// Crash-safe write: a kill mid-save must never leave a truncated character file. Write a temp file,
		// fsync it, keep the previous good file as <name>.json.bak, then atomically move the temp over the save.
		try {
			File tmp = new File(file.getPath() + ".tmp");
			try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp);
					java.io.OutputStreamWriter writer = new java.io.OutputStreamWriter(out, java.nio.charset.StandardCharsets.UTF_8)) {
				writer.write(builder.toJson(save));
				writer.flush();
				out.getFD().sync();
			}
			if (file.exists()) {
				java.nio.file.Files.copy(file.toPath(), new File(file.getPath() + ".bak").toPath(),
						java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
			java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
					java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		} catch (Exception e) {
			Server.getLogger().log(Level.SEVERE, "An error has occurred while saving a character file!", e);
            throw new RuntimeException(e);
		}
    }

    @Override
    public boolean exists(String username) {
        String formattedUsername = Misc.formatPlayerName(username.toLowerCase());
        return new File(PATH + formattedUsername + ".json").exists();
    }

    @Override
    public String encryptPassword(String plainPassword) {
        // TODO: Fix password encryption for JSON
        return plainPassword;
    }

    @Override
    public boolean checkPassword(String plainPassword, PlayerSave playerSave) {
        // TODO: Fix password encryption for JSON
        return plainPassword.equals(playerSave.getPasswordHashWithSalt());
    }

    private void setupDirectory(File file) {
        file.getParentFile().setWritable(true);
        if (!file.getParentFile().exists()) {
            try {
                file.getParentFile().mkdirs();
            } catch (SecurityException e) {
                System.out.println("Unable to create directory for player data!");
                throw new RuntimeException(e);
            }
        }
    }
}
