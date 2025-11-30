# AI-MUSIC-SORTER-JAVA-SERVICE

## Description

AiMusicSorter is a Java-based backend service designed to automatically organize your music files. Using OpenAI’s GPT models, it analyzes filenames to infer song, artist, album, or mood and then sorts them into the appropriate folders while suggesting standardized file names in the format Artist - Song.

The service continuously monitors an inbox folder and moves files to designated music folders based on AI-generated recommendations. If a file cannot be confidently classified, it remains in the inbox for manual review.

This service is intended for personal music libraries or multi-user setups where consistent organization is needed.
## Technologies

AiMusicSorter uses the following technologies:

* JAVA11 - Java 11 SDK
* MAVEN - Build and dependency management tool
* OPENAI JAVA SDK - OpenAI client for Java
* SCHEDULER - Java ScheduledExecutorService for periodic processing

## Installation

Clone the repository:

```
git clone https://github.com/deian-mishev/minimal-music-sorter.git
cd minimal-music-sorter
```

Build the project and download dependencies using Maven:

```
mvn clean install
```

Set required environment variables:

```
export OPENAI_API_KEY="your_openai_api_key"
export ROOT_FOLDER="/path/to/your/music/inbox"
```

## Application Structure

Recommended folder structure for your music library:

```
root_folder/
  Inbox/
  Rock/
  Pop/
  Jazz/
  Classical/
  Electronic/
  ...
```

The AI will move and rename files from the Inbox to the appropriate folders based on filename analysis.

## Running and Building

Using Maven:

```
mvn compile exec:java
```

The service will monitor the inbox and organize files every minute.

## Todos

* Add support for reading embedded ID3 metadata to improve classification
* Add logging and error tracking
* Implement database storage for user history and statistics
* Add unit and integration tests

## License

This project is open-source. Contributions are welcome.

## References

- JAVA11: [https://www.oracle.com/java/technologies/javase-jdk11-downloads.html](https://www.oracle.com/java/technologies/javase-jdk11-downloads.html)
- MAVEN: [https://maven.apache.org](https://maven.apache.org)
- OPENAI JAVA SDK: [https://github.com/openai/openai-java](https://github.com/openai/openai-java)
- SCHEDULER: [https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ScheduledExecutorService.html](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ScheduledExecutorService.html)
