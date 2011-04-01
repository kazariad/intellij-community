/*
 * Copyright 2000-2009 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.psi.codeStyle;

import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.ArrayUtil;
import com.intellij.util.Function;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.FList;
import com.intellij.util.text.StringTokenizer;
import org.apache.oro.text.regex.MalformedPatternException;
import org.apache.oro.text.regex.Pattern;
import org.apache.oro.text.regex.Perl5Compiler;
import org.apache.oro.text.regex.Perl5Matcher;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class NameUtil {
  private static final Function<String,String> LOWERCASE_MAPPING = new Function<String, String>() {
    public String fun(final String s) {
      return s.toLowerCase();
    }
  };
  private static final int MAX_LENGTH = 40;

  private NameUtil() {}

  public static List<String> nameToWordsLowerCase(String name){
    return ContainerUtil.map(nameToWords(name), LOWERCASE_MAPPING);
  }

  public static String[] nameToWords(String name){
    ArrayList<String> array = new ArrayList<String>();
    int index = 0;
    int wordStart;

    while(index < name.length()){
      wordStart = index;
      int upperCaseCount = 0;
      int lowerCaseCount = 0;
      int digitCount = 0;
      int specialCount = 0;
      while(index < name.length()){
        char c = name.charAt(index);
        if (Character.isDigit(c)){
          if (upperCaseCount > 0 || lowerCaseCount > 0 || specialCount > 0) break;
          digitCount++;
        }
        else if (Character.isUpperCase(c)){
          if (lowerCaseCount > 0 || digitCount > 0 || specialCount > 0) break;
          upperCaseCount++;
        }
        else if (Character.isLowerCase(c)){
          if (digitCount > 0 || specialCount > 0) break;
          if (upperCaseCount > 1) {
            index--;
            break;
          }
          lowerCaseCount++;
        }
        else{
          if (upperCaseCount > 0 || lowerCaseCount > 0 || digitCount > 0) break;
          specialCount++;
        }
        index++;
      }
      String word = name.substring(wordStart, index);
      array.add(word);
    }
    return ArrayUtil.toStringArray(array);
  }

  public static String buildRegexp(String pattern, int exactPrefixLen, boolean allowToUpper, boolean allowToLower) {
    return buildRegexp(pattern, exactPrefixLen, allowToUpper, allowToLower, false, false);
  }

  public static String buildRegexp(String pattern,
                                   int exactPrefixLen,
                                   boolean allowToUpper,
                                   boolean allowToLower,
                                   boolean lowerCaseWords,
                                   boolean forCompletion) {
    final int eol = pattern.indexOf('\n');
    if (eol != -1) {
      pattern = pattern.substring(0, eol);
    }
    if (pattern.length() >= MAX_LENGTH) {
      pattern = pattern.substring(0, MAX_LENGTH);
    }

    @NonNls final StringBuffer buffer = new StringBuffer();
    boolean lastIsUppercase = false;
    boolean prevIsUppercase = false;
    final boolean endsWithSpace = !forCompletion && StringUtil.endsWithChar(pattern, ' ');
    if (!forCompletion) {
      pattern = pattern.trim();
    }
    exactPrefixLen = Math.min(exactPrefixLen, pattern.length());
    /*final boolean uppercaseOnly = containsOnlyUppercaseLetters(pattern.substring(exactPrefixLen));
    if (uppercaseOnly) {
      allowToLower = false;
    }*/
    if (exactPrefixLen > 0) {
      char c = pattern.charAt(exactPrefixLen - 1);
      prevIsUppercase = Character.isUpperCase(c) || Character.isDigit(c);
    }

    for (int i = 0; i != exactPrefixLen; ++i) {
      final char c = pattern.charAt(i);
      if (Character.isLetterOrDigit(c)) {
        buffer.append(c);
      }
      else {
        // for standard RegExp engine
        // buffer.append("\\u");
        // buffer.append(Integer.toHexString(c + 0x20000).substring(1));

        // for OROMATCHER RegExp engine
        buffer.append("\\").append(c);
        //buffer.append(Integer.toHexString(c + 0x20000).substring(2));
      }
    }

    if (exactPrefixLen == 0) {
      buffer.append("_*");  // ignore leading underscores
    }

    boolean firstIdentifierLetter = (exactPrefixLen == 0);
    //System.out.println("pattern = " + pattern);
    for (int i = exactPrefixLen; i < pattern.length(); i++) {
      final char c = pattern.charAt(i);
      lastIsUppercase = false;
      if (Character.isLetterOrDigit(c)) {
        prevIsUppercase = false;

        // This logic allows to use uppercase letters only to catch the name like PDM for PsiDocumentManager
        if (Character.isUpperCase(c) || Character.isDigit(c)) {
          prevIsUppercase = true;
          lastIsUppercase = true;

          buffer.append('(');

          if (!firstIdentifierLetter) {
            buffer.append("[a-z\\s0-9\\$]*");
          }

          buffer.append(c);
          if (allowToLower) {
            buffer.append('|');
            buffer.append(Character.toLowerCase(c));
          }
          if (!firstIdentifierLetter) {
            buffer.append("|[A-Za-z\\s0-9\\$]*[_-]+[");
            buffer.append(c);
            buffer.append(Character.toLowerCase(c));
            buffer.append("]");
          }
          buffer.append(')');
        }
        else if (Character.isLowerCase(c) && allowToUpper) {
          buffer.append('[');
          buffer.append(c);
          buffer.append(Character.toUpperCase(c));
          buffer.append(']');
          if (lowerCaseWords) {
            buffer.append("([a-z\\s0-9\\$]*[-_]+)?");
          }
        }
        else {
          buffer.append(c);
        }

        firstIdentifierLetter = false;
      }
      else if (c == '*') {
        buffer.append(".*");
        firstIdentifierLetter = true;
      }
      else if (c == '.') {
        if (!firstIdentifierLetter) {
          buffer.append("[a-z\\s0-9\\$]*\\.");
        }
        else {
          buffer.append("\\.");
        }
        firstIdentifierLetter = true;
      }
      else if (c == ' ') {
        buffer.append("([a-z\\s0-9\\$_-]*[\\ _-]+)+");
        firstIdentifierLetter = true;
      }
      else {
        if (c == ':' || prevIsUppercase) {
          buffer.append("[A-Za-z\\s0-9\\$]*");
        }

        firstIdentifierLetter = true;
        // for standard RegExp engine
        // buffer.append("\\u");
        // buffer.append(Integer.toHexString(c + 0x20000).substring(1));

        // for OROMATCHER RegExp engine
        buffer.append("\\").append(c);
        //buffer.append(Integer.toHexString(c + 0x20000).substring(3));
      }
    }

    if (!endsWithSpace) {
      buffer.append(".*");
    }
    else if (lastIsUppercase) {
      buffer.append("[a-z\\s0-9\\$]*");
    }

    //System.out.println("rx = " + buffer.toString());
    return buffer.toString();
  }

  /**
   * Splits an identifier into words, separated with underscores or upper-case characters
   * (camel-case).
   *
   * @param name the identifier to split.
   * @return the array of strings into which the identifier has been split.
   */
  public static String[] splitNameIntoWords(@NotNull String name) {
    final String[] underlineDelimited = name.split("_");
    List<String> result = new ArrayList<String>();
    for (String word : underlineDelimited) {
      addAllWords(word, result);
    }
    return ArrayUtil.toStringArray(result);
  }

  public static List<String> getSuggestionsByName(String name,
                                                   String prefix,
                                                   String suffix,
                                                   boolean upperCaseStyle,
                                                   boolean preferLongerNames, boolean isArray) {
    ArrayList<String> answer = new ArrayList<String>();
    String[] words = nameToWords(name);

    for (int step = 0; step < words.length; step++) {
      int wordCount = preferLongerNames ? words.length - step : step + 1;

      String startWord = words[words.length - wordCount];
      char c = startWord.charAt(0);
      if( c == '_' || !Character.isJavaIdentifierStart( c ) )
      {
        continue;
      }

      StringBuilder buffer = new StringBuilder();
      buffer.append(prefix);

      if (upperCaseStyle) {
        startWord = startWord.toUpperCase();
      }
      else {
        if (prefix.length() == 0 || StringUtil.endsWithChar(prefix, '_')) {
          startWord = startWord.toLowerCase();
        }
        else {
          startWord = Character.toUpperCase(c) + startWord.substring(1);
        }
      }
      buffer.append(startWord);

      for (int i = words.length - wordCount + 1; i < words.length; i++) {
        String word = words[i];
        String prevWord = words[i - 1];
        if (upperCaseStyle) {
          word = word.toUpperCase();
          if (prevWord.charAt(prevWord.length() - 1) != '_' && word.charAt(0) != '_') {
            word = "_" + word;
          }
        }
        else {
          if (prevWord.charAt(prevWord.length() - 1) == '_') {
            word = word.toLowerCase();
          }
        }
        buffer.append(word);
      }

      String suggestion = buffer.toString();

      if (isArray) {
        suggestion = StringUtil.pluralize(suggestion);
        if (upperCaseStyle) {
          suggestion = suggestion.toUpperCase();
        }
      }

      answer.add(suggestion + suffix);
    }
    return answer;
  }

  private enum WordState { NO_WORD, PREV_UC, WORD }

  private static boolean isWordStart(char p) {
    return Character.isUpperCase(p) || Character.isDigit(p);
  }

  private static void addAllWords(String text, List<String> result) {
    int start = 0;
    WordState state = WordState.NO_WORD;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (state) {
        case NO_WORD:
          if (!isWordStart(c)) {
            state = WordState.WORD;
          }
          else {
            state = WordState.PREV_UC;
          }
          break;
        case PREV_UC:
          if (!isWordStart(c)) {
            start = startNewWord(text, result, start, i - 1);
            state = WordState.WORD;
          }
          else {
            state = WordState.PREV_UC;
          }
          break;
        case WORD:
          if (isWordStart(c)) {
            start = startNewWord(text, result, start, i);
            state = WordState.PREV_UC;
          }
          break;
      }
    }
    startNewWord(text, result, start, text.length());
  }

  private static int startNewWord(String word, List<String> result, int start, int end) {
    if (end > start) {
      result.add(word.substring(start, end));
    }
    return end;
  }

  public interface Matcher {
    boolean matches(String name);
  }

  public static Matcher buildCompletionMatcher(String pattern, int exactPrefixLen, boolean allowToUpper, boolean allowToLower) {
    MatchingCaseSensitivity options = !allowToLower && !allowToUpper ? MatchingCaseSensitivity.ALL : exactPrefixLen > 0 ? MatchingCaseSensitivity.FIRST_LETTER : MatchingCaseSensitivity.NONE;
    return buildMatcher(pattern, buildRegexp(pattern, exactPrefixLen, allowToUpper, allowToLower, false, true), options);
  }

  public static Matcher buildMatcher(String pattern, int exactPrefixLen, boolean allowToUpper, boolean allowToLower) {
    MatchingCaseSensitivity options = !allowToLower && !allowToUpper ? MatchingCaseSensitivity.ALL : exactPrefixLen > 0 ? MatchingCaseSensitivity.FIRST_LETTER : MatchingCaseSensitivity.NONE;
    return buildMatcher(pattern, buildRegexp(pattern, exactPrefixLen, allowToUpper, allowToLower), options);
  }

  public static Matcher buildMatcher(String pattern, int exactPrefixLen, boolean allowToUpper, boolean allowToLower, boolean lowerCaseWords) {
    MatchingCaseSensitivity options = !allowToLower && !allowToUpper ? MatchingCaseSensitivity.ALL : exactPrefixLen > 0 ? MatchingCaseSensitivity.FIRST_LETTER : MatchingCaseSensitivity.NONE;
    return buildMatcher(pattern, buildRegexp(pattern, exactPrefixLen, allowToUpper, allowToLower, lowerCaseWords, false), options);
  }

  public static boolean isUseMinusculeHumpMatcher() {
    return Registry.is("minuscule.humps.matching");
  }

  private static Matcher buildMatcher(final String pattern, String regexp, MatchingCaseSensitivity options) {
    return isUseMinusculeHumpMatcher() ? new MinusculeMatcher(pattern, options) : new OptimizedMatcher(pattern, regexp);
  }

  private static class OptimizedMatcher implements Matcher {
    private final char[] myPreparedPattern;
    private final boolean myEnsureFirstSymbolsMatch;
    private final Perl5Matcher myMatcher;
    private final Pattern myPattern;

    public OptimizedMatcher(String pattern, String regexp) {
      myPreparedPattern = preparePattern(pattern).toCharArray();
      myEnsureFirstSymbolsMatch = pattern.length() > 0 && Character.isLetterOrDigit(pattern.charAt(0));
      try {
        myPattern = new Perl5Compiler().compile(regexp);
      }
      catch (MalformedPatternException e) {
        throw new RuntimeException(e);
      }
      myMatcher = new Perl5Matcher();
    }

    public boolean matches(String name) {
      if (!prefilter(name, myPreparedPattern)) {
        return false;
      }

      try {
        return myMatcher.matches(name, myPattern);
      }
      catch (ArrayIndexOutOfBoundsException e) {
        throw new RuntimeException("Name=" + name + "; pattern=" + myPattern.getPattern() , e);
      }
    }

    private static String preparePattern(String pattern) {
      StringBuilder builder = new StringBuilder();
      for (int i = 0; i < pattern.length(); i++) {
        char c = pattern.charAt(i);
        if (Character.isLetterOrDigit(c)) {
          builder.append(StringUtil.toLowerCase(c));
        }
      }
      return builder.toString();
    }

    /*
     * Don't try regexp matcher on names, which do not contain all the alphanumerics from the pattern in pattern's original order.
     */
    private boolean prefilter(String name, char[] pattern) {
      int patternIndex = 0;
      int nameIndex = 0;

      int patternLen = pattern.length;
      int nameLen = name.length();

      if (myEnsureFirstSymbolsMatch) {
        while (nameIndex < nameLen && name.charAt(nameIndex) == '_') {
          nameIndex++;
        }

        if (patternLen == 0 || nameIndex >= nameLen) return false;
        if (StringUtil.toLowerCase(name.charAt(nameIndex)) != pattern[0]) return false;

        nameIndex++;
        patternIndex++;
      }

      while (patternIndex < patternLen) {
        char c = pattern[patternIndex++];

        while (true) {
          if (nameIndex >= nameLen) return false;
          if (StringUtil.toLowerCase(name.charAt(nameIndex++)) == c) break;
        }
      }

      return true;
    }
  }

  public enum MatchingCaseSensitivity {
    NONE, FIRST_LETTER, ALL
  }

  public static class MinusculeMatcher implements Matcher {
    private final char[] myPattern;
    private final MatchingCaseSensitivity myOptions;

    public MinusculeMatcher(String pattern, MatchingCaseSensitivity options) {
      myOptions = options;
      myPattern = StringUtil.trimEnd(pattern, "* ").replaceAll(":", "\\*:").replaceAll("\\.", "\\*\\.").toCharArray();
    }

    @Nullable
    private FList<TextRange> matchName(int patternIndex, List<String> words, int wordIndex, int insideWord, int wordStart) {
      if (patternIndex == myPattern.length) {
        return FList.emptyList();
      }
      if (wordIndex == words.size()) {
        return null;
      }

      String word = words.get(wordIndex);

      if ('*' == myPattern[patternIndex]) {
        return handleAsterisk(patternIndex, words, wordIndex, insideWord, wordStart);
      }

      if (patternIndex == 0 && myOptions != MatchingCaseSensitivity.NONE && word.charAt(insideWord) != myPattern[0]) {
        return null;
      }

      if (isWordSeparator(word.charAt(insideWord))) {
        assert word.length() == 1 : "'" + word + "'";
        char p = myPattern[patternIndex];
        int nextStart = wordStart + word.length();
        if (isWordSeparator(p)) {
          if (myOptions != MatchingCaseSensitivity.NONE &&
              wordIndex == 0 && words.size() > 1 && patternIndex + 1 < myPattern.length &&
              isWordSeparator(words.get(1).charAt(0)) && !isWordSeparator(myPattern[patternIndex + 1])) {
            return null;
          }

          return matchName(patternIndex + 1, words, wordIndex + 1, 0, nextStart);
        }

        return matchName(patternIndex, words, wordIndex + 1, 0, nextStart);
      }

      if (StringUtil.toLowerCase(word.charAt(insideWord)) != StringUtil.toLowerCase(myPattern[patternIndex])) {
        return null;
      }

      boolean uppers = isWordStart(myPattern[patternIndex]);

      int i = 1;
      while (true) {
        if (patternIndex + i == myPattern.length) {
          return FList.<TextRange>emptyList().prepend(TextRange.from(wordStart + insideWord, i));
        }
        if (i == word.length() - insideWord) {
          break;
        }
        char p = myPattern[patternIndex + i];
        if (uppers && isWordStart(p) && myOptions != MatchingCaseSensitivity.ALL) {
          p = StringUtil.toLowerCase(p);
        } else {
          uppers = false;
        }

        char w = word.charAt(insideWord + i);
        if (myOptions != MatchingCaseSensitivity.ALL) {
          w = StringUtil.toLowerCase(w);
        }
        if (w != p) {
          break;
        }
        i++;
      }
      // there's more in the pattern, but no more words
      if (wordIndex == words.size() - 1) {
        if (patternIndex + i == myPattern.length - 1 && ' ' == myPattern[patternIndex + i] && i == word.length() - insideWord) {
          return FList.<TextRange>emptyList().prepend(TextRange.from(wordStart + insideWord, i));
        }

        return null;
      }

      int nextWord = wordStart + word.length();
      while (i > 0) {
        FList<TextRange> ranges = matchName(patternIndex + i, words, wordIndex + 1, 0, nextWord);
        if (ranges != null) {
          return ranges.prepend(TextRange.from(wordStart + insideWord, i));
        }
        i--;
      }
      return null;
    }

    @Nullable
    private FList<TextRange> handleAsterisk(int patternIndex, List<String> words, int wordIndex, int insideWord, int wordStart) {
      while ('*' == myPattern[patternIndex]) {
        patternIndex++;
        if (patternIndex == myPattern.length) {
          return FList.emptyList();
        }
      }

      String nextChar = String.valueOf(myPattern[patternIndex]);

      int fromIndex = insideWord;
      for (int i = wordIndex; i < words.size(); i++) {
        String s = words.get(i);
        while (true) {
          int next = StringUtil.indexOfIgnoreCase(s, nextChar, fromIndex);
          if (next < 0) {
            break;
          }
          FList<TextRange> ranges = matchName(patternIndex, words, i, next, wordStart);
          if (ranges != null) {
            return ranges;
          }
          fromIndex = next + 1;
        }
        fromIndex = 0;
        wordStart += s.length();
      }
      return null;
    }

    private static boolean isWordSeparator(char c) {
      return Character.isWhitespace(c) || c == '_' || c == '-';
    }

    @Override
    public boolean matches(String name) {
      return matchingFragments(name) != null;
    }

    @Nullable
    public Iterable<TextRange> matchingFragments(String name) {
      StringTokenizer tokenizer = new StringTokenizer(name, " -_.:/", true);
      List<String> words = new ArrayList<String>();
      while (tokenizer.hasMoreTokens()) {
        String token = tokenizer.nextToken();
        addAllWords(token, words);
      }

      if (words.isEmpty()) {
        return myPattern.length == 0 ? Collections.<TextRange>emptyList() : null;
      }

      return matchName(0, words, 0, 0, 0);
    }
  }
}
