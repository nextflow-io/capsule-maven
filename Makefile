compile:
	./gradlew compileJava compileTestJava

check:
	./gradlew check

upload:
	./gradlew uploadArchives

release:
	./gradlew closeAndReleaseRepository
