package com.docanonymizer.adapter.gazetteer;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Immutable snapshot of optional local literal rules. */
public record UserRules(List<String> people, List<String> organizations, List<String> terms,
                        List<String> excludedPeople, List<String> excludedOrganizations, List<String> excludedTerms) {
    public UserRules(List<String> people, List<String> organizations, List<String> terms) {
        this(people, organizations, terms, List.of(), List.of(), List.of());
    }

    public UserRules {
        people = List.copyOf(people);
        organizations = List.copyOf(organizations);
        terms = List.copyOf(terms);
        excludedPeople = List.copyOf(excludedPeople);
        excludedOrganizations = List.copyOf(excludedOrganizations);
        excludedTerms = List.copyOf(excludedTerms);
    }

    public static UserRules loadDefault() {
        String override = System.getProperty("doc.anonymizer.rules");
        Path path = override == null ? Path.of(System.getProperty("user.home"), ".anonimuse", "rules.txt")
                : Path.of(override);
        if (override == null && Files.notExists(path)) return new UserRules(List.of(), List.of(), List.of());
        return load(path);
    }

    public static UserRules load(Path path) {
        String content;
        try {
            byte[] bytes = Files.readAllBytes(path);
            content = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Invalid UTF-8 rules file: " + path, e);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read rules file: " + path, e);
        }
        List<String> people = new ArrayList<>(), organizations = new ArrayList<>(), terms = new ArrayList<>();
        List<String> excludedPeople = new ArrayList<>(), excludedOrganizations = new ArrayList<>(), excludedTerms = new ArrayList<>();
        String[] lines = content.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int colon = line.indexOf(':');
            if (colon < 0 || !line.substring(colon + 1).startsWith(" "))
                throw invalid(path, i + 1);
            String value = line.substring(colon + 1).strip();
            if (value.isEmpty() || value.indexOf('\u0000') >= 0) throw invalid(path, i + 1);
            switch (line.substring(0, colon)) {
                case "person" -> people.add(value);
                case "organization" -> organizations.add(value);
                case "term" -> terms.add(value);
                case "exclude-person" -> excludedPeople.add(value);
                case "exclude-organization" -> excludedOrganizations.add(value);
                case "exclude-term" -> excludedTerms.add(value);
                default -> throw invalid(path, i + 1);
            }
        }
        return new UserRules(people, organizations, terms, excludedPeople, excludedOrganizations, excludedTerms);
    }

    private static IllegalArgumentException invalid(Path path, int line) {
        return new IllegalArgumentException("Invalid rule at " + path + ":" + line
                + "; expected person: <given name>, organization: <phrase>, term: <phrase>, "
                + "exclude-person: <full name>, exclude-organization: <phrase>, or exclude-term: <phrase>");
    }
}
