# Polski korpus

`fixtures/pl-evaluation-corpus.jsonl` to ręcznie napisany korpus polski: 174 grupy, 540 zdań.

- Grupy `g0001`–`g0123` są polskimi odpowiednikami grup z `fixtures/en-evaluation-corpus.jsonl`:
  481 odpowiedników oraz 8 dodatkowych parafraz istniejących scenariuszy, wykorzystujących miejscownik miesiąca
  i słowny dzień, np. „w maju dziesiątego”.
- Grupy od `g0124` to zdania polskie bez angielskiego odpowiednika, wszystkie w warstwie `core`.

Zdania nie powstały z generatora kombinacji słów.

ID grup służą do porównania z EN. Daty, godziny, czas trwania, powtórzenia,
przypomnienia, priorytety, ID miejsc docelowych i kolejność kandydatów pozostają
takie same. Tytuły są polskie i dosłowne: bez dopisywania przyimków, rozwijania
skrótów czy parafrazowania treści. Nazwy istniejących miejsc oraz tytuły własne
Friday Night Lights i Saturday Night Live zachowano w oryginale.

Źródłowy EN, SHA-256: `16ad96e45e79c5b789bf86a898e5e3d03c7f57dcd4608ace58d81aafd7a932cf`.

## Kontekst PL

Obowiązują reguły z `CORPUS_SPEC.md` z poniższymi zmianami językowymi:

- Język `pl`, kolejność dat **dzień–miesiąc**: `05.10` = 5 października.
  ISO `2026-10-05` nadal oznacza tę samą datę.
- Teraz: środa 2026-09-23, 12:00 UTC. Początek tygodnia: poniedziałek.
  Domyślna długość wydarzenia: 60 minut.
- Listy: Praca (`work`, tag `#praca`), Osobiste (`personal`, `#osobiste`),
  Dom (`home`, `#dom`). Kalendarze: Zespół (`team`, `#zespół`),
  Rodzina (`family`, `#rodzina`). ID pozostają zgodne z EN; nazwy są polskie.
- `+`, `t:task`, `type:task` zachowują znaczenie technicznych znaczników.
  „zadanie”, „do zrobienia” i „przypomnij mi:” są miękkimi wskazówkami zadania.
  „przypomnij mi:” nie oznacza automatycznie przypomnienia przy terminie.
- „jutro”, „na jutro”, „do jutra” wskazują tę samą datę terminu.
  „w piątek”, „na piątek”, „do piątku” wskazują najbliższy piątek.
  „przyszły” zachowuje konwencję EN: dzień w następnym tygodniu kalendarzowym.
- Nazwy dni obejmują odmianę: „środa”, „w środę”, „do środy”, „w przyszłą
  środę”, „od przyszłego poniedziałku”, „we wtorki”, „w czwartki”.
  „co poniedziałek”, „we wtorki” i „w dni robocze” oznaczają powtórzenia.
- Daty obejmują „10 maja”, „dziesiątego maja”, „do 10 maja”, „w maju
  dziesiątego”; analogicznie w marcu, kwietniu, czerwcu, sierpniu,
  wrześniu i październiku. Słowny dzień to dzień miesiąca, nie godzina.
- Godziny obejmują „o 17”, „o piątej po południu”, „o siódmej rano”,
  „o ósmej wieczorem”, „w południe”. „wpół do piątej po południu” = 16:30,
  „wpół do szóstej po południu” = 17:30, „wpół do dwunastej” = 11:30.
  Przedział „od 14 do 16” lub „między 14 a 16” trwa od 14:00 do 16:00.
  Liczba bez kwalifikatora zachowuje konwencję EN dla godzin 1–7 / 8–12.
- „przez pół godziny”, „przez godzinę”, „przez 90 minut” określają czas
  trwania. „przez cały dzień” i „na cały dzień” oznaczają wydarzenie całodniowe.
- „pilne”, „wysoki priorytet”, „średni priorytet”, „niski priorytet”
  odpowiadają priorytetom EN. Symbole `!`, `!!`, `!!!` pozostają bez zmian.
- „przypomnij 10 minut wcześniej”, „powiadom 10 minut przed” oznaczają
  przypomnienie przed początkiem. „ustaw przypomnienie” jest częścią prośby.
- „w Main Office”, `@"Studio 2"` wskazują miejsce wydarzenia.
  W kandydacie zadania całe wyrażenie miejsca pozostaje w tytule.
  Sama końcówka „-u”, „-ą” czy „-em” w nazwie osoby nie jest metadanymi.

Przybliżenia „koło”, „około”, „mniej więcej”, „koniec dnia”, „po lunchu”,
potoczne prośby i literówki są opisane w `notes` odpowiednich grup `stretch`.
Nie dopasowano oczekiwań do aktualnego zachowania parsera.

## Różnice, których nie przenosimy mechanicznie

- `g0014`, `g0033`, `g0056`, `g0065`, `g0090`, `g0106`: angielskie May 10,
  March 15 itd. mogą oznaczać osobę i godzinę lub miesiąc i dzień. Naturalne
  polskie „10 maja”, „15 marca”, „4 sierpnia” rozstrzygają datę. Te grupy
  są `core`, bez sztucznych alternatyw osobowych.
- W pułapkach osobowych May, April, June, March zaadaptowano na nazwiska
  Maj, Kwiecień, Czerwiec, Marzec. Pozwala to badać naturalne „z Majem”,
  „do Maja”, „z Kwietniem”, „do Kwietnia”, „z Czerwcem”, „do Czerwca”,
  „do Marca”, zachowując scenariusz kontaktu z osobą.
- `g0069`: angielska gra march/March znika w „maszeruj do biura”.
  Polski odpowiednik jest zwykłym zadaniem `core`.
- `g0116`: polski nie ma rodzajnika the, więc tytuł jest taki jak w g0002.
- `g0121`: dosłowny skrót „inż.” zachowujemy w tytule, bez rozwijania.
- `g0122`: urwany zapis „lunch Sara” pozostaje niepoprawiony. To świadomy
  odpowiednik błędnego EN, nie wzorzec poprawnej polszczyzny.
- `g0123`: brytyjskie half five oddano jako „wpół do szóstej po południu”,
  z zachowaniem 17:30; nie tłumaczymy tego na 16:30.

Nie dodano nowych spotkań, zadań ani zakresów dat. Przykład „między majem
a sierpniem” wymagałby nowego scenariusza, którego w EN nie ma. Ten korpus
nie jest pełnym testem wszystkich przypadków fleksyjnych każdego miesiąca.

## Uruchamianie

Biblioteka ma roboczy słownik polski (`Configuration(language = "pl")`). Świat, dla którego napisano korpus (język,
czas odniesienia, strefa, nazwy list i kalendarzy), jest w pliku obok: `fixtures/pl-evaluation-context.json`.
Kolejność dzień–miesiąc wynika z samego języka i nie wymaga ustawienia.

```sh
make evaluate CORPUS=fixtures/pl-evaluation-corpus.jsonl              # Swift z modelem, bez sprawdzania pisowni
make evaluate CORPUS=fixtures/pl-evaluation-corpus.jsonl SPELLING=1   # z systemowym sprawdzaniem pisowni
```

Na razie korpus polski jest tylko tablicą wyników, nie bramką w `make check`: model nie był jeszcze dostrajany na
polskich zdaniach i czyta je dzięki temu, czego nauczył się po angielsku. Gdy będzie, lista zdań, które nie przechodzą,
wróci jako `fixtures/pl-evaluation-known-failures.txt` przez `make accept-corpus CORPUS=fixtures/pl-evaluation-corpus.jsonl`.
