package mp3.sorting;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.mp3.MP3File;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.id3.ID3v24Tag;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Collectors;

public class AiMusicSorter {

    private static final String GPT_PROMPT_TEMPLATE = """
            You are a music-file organizer. For each filename in the list below, extract the most likely Artist and Song based only on the filename. Use this to choose the most appropriate folder from the allowed list and propose a cleaned filename in the format: Artist - Song.
            
            Available folders:
            %s
            
            Rules:
            1. Respond strictly in the format:
               original_filename → folder_name → new_filename
            
            Example:
               Old Song.mp3 → MyFolder → Artist - Track
            
            2. If a folder is not available then think of one based on the artist name
            3. new_filename must use the format: Artist - Song (with proper capitalization, without years, numbers, rip tags, quality tags, or extra symbols).
            4. Never output explanations, comments, or extra text.
            
            Files:
            %s""";

    private final Path root;
    private final OpenAIClient client;

    public AiMusicSorter(Path rootFolder) {
        this.root = rootFolder;
        String apiKey = System.getenv("OPENAI_API_KEY");

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Environment variable OPENAI_API_KEY is not set!");
        }

        client = OpenAIOkHttpClient.builder().apiKey(apiKey).build();
    }

    public void start() throws Exception {
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("Inbox folder does not exist: " + root);
        }

        System.out.println("Starting batch sorter on root: " + root);

        Files.walkFileTree(root, new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {

                if (file.toString().toLowerCase().endsWith(".mp3")) {
                    try {
                        fixMp3(file);
                    } catch (Exception e) {
                        System.err.println("Error handling " + file + ": " + e.getMessage());
                    }
                }

                return FileVisitResult.CONTINUE;
            }
        });

        processInboxBatch();
    }

    private static void fixMp3(Path file) throws Exception {
        Path parent = file.getParent();
        String folderName = parent.getFileName().toString();

        String fixedName = file.getFileName().toString().replaceAll("(?i)\\.mp3$", "");
        fixedName = fixedName + ".mp3";

        Path fixedFile = parent.resolve(fixedName);

        if (!file.equals(fixedFile)) {
            Files.move(file, fixedFile, StandardCopyOption.REPLACE_EXISTING);
            file = fixedFile;
        }
        String baseName = fixedName.replace(".mp3", "");
        String title;
        if (baseName.contains(" - ")) {
            title = baseName.substring(baseName.indexOf(" - ") + 3).trim();
        } else {
            title = baseName;
        }

        MP3File mp3File = (MP3File) AudioFileIO.read(file.toFile());

        Tag tag = mp3File.getTag();
        if (tag != null) {
            for (FieldKey fieldKey : FieldKey.values()) {
                tag.deleteField(fieldKey);
            }
        }
        mp3File.setID3v1Tag(null);
        mp3File.setID3v2Tag(null);

        Tag newTag = new ID3v24Tag();
        newTag.setField(FieldKey.ARTIST, folderName);
        newTag.setField(FieldKey.ALBUM, folderName);
        newTag.setField(FieldKey.TITLE, title);
        mp3File.setTag(newTag);
        mp3File.commit();

        String newName = folderName + " - " + title + ".mp3";
        Path renamed = parent.resolve(newName);

        if (!fixedFile.equals(renamed)) {
            Files.move(fixedFile, renamed, StandardCopyOption.REPLACE_EXISTING);
        }

        System.out.println("Updated: " + renamed);
    }

    private void processInboxBatch() throws Exception {
        List<Path> files = listInboxFiles();

        if (files.isEmpty()) {
            System.out.println("No files to process.");
            return;
        }

        Set<String> validFolders = new HashSet<>(listValidFolders());

        Map<String, FileMoveInfo> decisions = askChatGPTBatch(files, validFolders);

        for (Path file : files) {
            String fname = file.getFileName().toString();
            FileMoveInfo targetInfo = decisions.get(fname);

            if (targetInfo != null) {
                Path targetFolder = root.resolve(targetInfo.folder);
                if (!Files.exists(targetFolder)) {
                    Files.createDirectories(targetFolder);
                }

                String extension = "";
                int dotIndex = fname.lastIndexOf('.');
                if (dotIndex >= 0) extension = fname.substring(dotIndex);

                String cleanNewName = stripExtension(targetInfo.newName);
                Path targetFile = targetFolder.resolve(cleanNewName + extension);

                try {
                    Files.move(file, targetFile, StandardCopyOption.REPLACE_EXISTING);
                    System.out.println("Moved & renamed " + fname + " → " + targetInfo.folder + "/" + targetFile.getFileName());
                } catch (IOException ex) {
                    System.err.println("Failed to move " + fname + ": " + ex.getMessage());
                }
            }
        }
    }

    private List<Path> listInboxFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        int limit = 50;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path p : stream) {
                if (Files.isRegularFile(p)) {
                    if (!p.toString().toLowerCase().endsWith(".mp3")) continue;
                    files.add(p);
                    limit--;
                }
                if (limit < 0) break;
            }
        }
        return files;
    }

    private List<String> listValidFolders() throws IOException {
        List<String> folders = new ArrayList<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path p : stream) {
                if (Files.isDirectory(p)) {
                    folders.add(p.getFileName().toString());
                }
            }
        }
        return folders;
    }

    private Map<String, FileMoveInfo> askChatGPTBatch(List<Path> files, Set<String> validFolders) {
        String folderList = String.join("\n", validFolders);
        String fileList = files.stream().map(f -> f.getFileName().toString()).collect(Collectors.joining("\n"));

        String prompt = String.format(GPT_PROMPT_TEMPLATE, folderList, fileList);

        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder().model(ChatModel.GPT_4_1).addUserMessage(prompt).build();

        ChatCompletion response = client.chat().completions().create(params);
        String reply = response.choices().getFirst().message().content().orElse("");

        Map<String, FileMoveInfo> map = new HashMap<>();
        for (String line : reply.split("\n")) {
            if (!line.contains("→")) continue;
            String[] parts = line.split("→");
            if (parts.length != 3) continue;

            String original = parts[0].replace("\"", "").trim();
            String folder = parts[1].replace("\"", "").trim();
            String newName = parts[2].replace("\"", "").trim();

            map.put(original, new FileMoveInfo(original, folder, newName));
        }

        return map;
    }

    private String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    public static void main(String[] args) throws Exception {
        String folder = System.getenv("ROOT_FOLDER");

        if (folder == null || folder.isBlank()) {
            throw new IllegalStateException("Environment variable ROOT_FOLDER is not set!");
        }

        AiMusicSorter sorter = new AiMusicSorter(Paths.get(folder).toAbsolutePath());
        sorter.start();

        System.out.println("Sorter is running. Press CTRL+C to exit.");
    }

    private static class FileMoveInfo {
        public final String folder;
        public final String newName;
        public final String original;

        public FileMoveInfo(String original, String folder, String newName) {
            this.original = original;
            this.folder = folder;
            this.newName = newName;
        }
    }
}