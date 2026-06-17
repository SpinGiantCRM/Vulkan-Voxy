.PHONY: check compile lint

check: compile

compile:
	./gradlew compileJava

lint:
	./gradlew compileJava --warning-mode all
