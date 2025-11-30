package mp3.sorting;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class AiMusicSorter {

    private static final String GPT_PROMPT_TEMPLATE =
            """
                    You are a smart music organizer. For each music file listed below, analyze the filename \
                    to infer the possible song, band, album, or mood. Use this context to decide the most \
                    appropriate folder AND suggest a new file name in the format: Artist - Song.
                    
                    Valid folder names:
                    %s
                    
                    Instructions:
                    1. Respond ONLY in the format:
                    original_filename → folder_name → new_filename
                    2. Do not invent folders. If unsure about the folder, leave the file in the inbox.
                    
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

        client = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .build();
    }

    public void start() {
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("Inbox folder does not exist: " + root);
        }

        System.out.println("Starting batch sorter on root: " + root);

        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

        executor.scheduleAtFixedRate(() -> {
            try {
                processInboxBatch();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, 0, 1, TimeUnit.MINUTES);
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

            if (targetInfo != null && validFolders.contains(targetInfo.folder)) {
                Path targetFolder = root.resolve(targetInfo.folder);
                if (!Files.exists(targetFolder)) {
                    Files.createDirectories(targetFolder);
                }

                String extension = "";
                int dotIndex = fname.lastIndexOf('.');
                if (dotIndex >= 0) extension = fname.substring(dotIndex);

                Path targetFile = targetFolder.resolve(targetInfo.newName + extension);

                Files.move(file, targetFile, StandardCopyOption.REPLACE_EXISTING);

                System.out.println("Moved & renamed " + fname + " → " + targetInfo.folder + "/" + targetFile.getFileName());
            } else {
                System.out.println("Left in inbox: " + fname + " (invalid folder or not recognized)");
            }
        }
    }

    private List<Path> listInboxFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path p : stream) {
                if (Files.isRegularFile(p)) files.add(p);
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
        String fileList = files.stream()
                .map(f -> "- " + f.getFileName())
                .reduce("", (a, b) -> a + "\n" + b);

        String prompt = String.format(
                GPT_PROMPT_TEMPLATE,
                folderList, fileList
        );

        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .model(ChatModel.GPT_4_TURBO)
                .addUserMessage(prompt)
                .build();

        ChatCompletion response = client.chat().completions().create(params);
        String reply = response.choices().getFirst().message().content().orElse("");

        Map<String, FileMoveInfo> map = new HashMap<>();
        for (String line : reply.split("\n")) {
            if (!line.contains("→")) continue;
            String[] parts = line.split("→");
            if (parts.length < 3) continue;

            String original = parts[0].trim();
            String folder = parts[1].trim();
            String newName = parts[2].trim();

            if (validFolders.contains(folder)) {
                map.put(original, new FileMoveInfo(original, folder, newName));
            }
        }

        return map;
    }

    public static void main(String[] args) {
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