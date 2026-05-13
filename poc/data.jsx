// Vestige · static data for the prototype
// One record per persona. One record per pattern (with its own traceHits).

// ─── PERSONAS ─────────────────────────────────────────────────────
// Each persona is a single record: identity + sample voice + roast cuts.
// Adding a new persona means adding one object.
const PERSONAS = [
  {
    id: 'witness',
    name: 'Witness',
    tagline: 'Observes. Names the pattern. Keeps quiet otherwise.',
    transcript: [
      { who: 'YOU',     dim: true,  text: "okay so the meeting was fine, i thought, but then like an hour after i just crashed again. i don't know. third tuesday in a row maybe." },
      { who: 'WITNESS', dim: false, text: "Fourth entry mentions Tuesday meetings. State before is cruising. State after is crashed. Worth noting." },
      { who: 'YOU',     dim: true,  text: "yeah it's a thing. i keep saying i'll move them but i don't. i just kind of brace." },
      { who: 'WITNESS', dim: false, text: "You said you'd move them on Apr 12 and Apr 26. Same wording both times." },
    ],
    roast: [
      'Tuesday meetings have a body count.',
      'She got mentioned. Then she got buried.',
      '"Tired" has three jobs. None of them pay.',
      'Same project, same dread, three times. The project remains undefeated.',
    ],
  },
  {
    id: 'hardass',
    name: 'Hardass',
    tagline: 'Sharper. Less padding. More action.',
    transcript: [
      { who: 'YOU',     dim: true,  text: "okay so the meeting was fine, i thought, but then like an hour after i just crashed again." },
      { who: 'HARDASS', dim: false, text: "You logged the same blocked task three times. What changes before the fourth." },
      { who: 'YOU',     dim: true,  text: "i don't know. probably nothing." },
      { who: 'HARDASS', dim: false, text: "Pick something or stop saying it." },
    ],
    roast: [
      'Tuesday meetings: 4–0.',
      'Project: 3. You: 0.',
      '"Tired" 23 times, three meanings, zero clarity.',
      "Four mentions, then radio silence. The radio's still off.",
    ],
  },
  {
    id: 'editor',
    name: 'Editor',
    tagline: "Cuts vague words until they confess.",
    transcript: [
      { who: 'YOU',    dim: true,  text: "i'm just tired. like, tired tired. it's been a week." },
      { who: 'EDITOR', dim: false, text: "You used 'tired' six ways this month. Three mean crashed. Name it cleaner." },
      { who: 'YOU',    dim: true,  text: "fine. crashed. it's been a crashed week." },
      { who: 'EDITOR', dim: false, text: "Better. That's the 7th 'crashed' in 30 days." },
    ],
    roast: [
      '"Tired" — 23 occurrences, three meanings. Tireduction needed.',
      '"Fine" lives in seven entries with "crashed." Pick a vocabulary.',
      'Mentioned in four entries, then filed under "didn\u2019t happen."',
    ],
  },
];

// ─── PATTERNS ─────────────────────────────────────────────────────
// status: 'active' | 'snoozed' | 'resolved' — the lifecycle is real.
// traceHits: number[] — days (0..29) the pattern showed up in the
// trailing 30-day window. Single source of truth — no more hitMaps.
const PATTERNS = [
  {
    id: 'tuesday-meetings',
    title: 'Tuesday Meetings',
    category: 'Aftermath',
    observation: 'Fourth entry mentions Tuesday meetings. State before: cruising. State after: crashed.',
    count: '4 of 12 entries',
    lastSeen: 'May 7',
    status: 'active',
    traceHits: [3, 10, 17, 24, 26, 28],
    sources: [
      { date: 'Apr 12', snippet: 'crashed after standup, slept 90 minutes through dinner' },
      { date: 'Apr 18', snippet: 'wired until 2am, replayed the meeting six times' },
      { date: 'Apr 26', snippet: 'same concrete shoes again, said i\u2019d move it, didn\u2019t' },
      { date: 'May 7',  snippet: 'told myself it was fine, then ate a sleeve of crackers' },
    ],
    vocab: ['crashed', 'fine', 'wired', 'concrete shoes', 'brace'],
  },
  {
    id: 'the-email',
    title: 'The Email',
    category: 'Concrete Shoes',
    observation: 'Mentioned in 7 entries since April 9. Not sent.',
    count: '7 of 31 entries',
    lastSeen: 'May 6',
    status: 'active',
    traceHits: [1, 5, 9, 13, 17, 21, 25, 28],
    sources: [
      { date: 'Apr 09', snippet: 'i\u2019ll send the email after lunch. definitely after lunch.' },
      { date: 'Apr 17', snippet: 'meant to do it. then i did literally everything else.' },
      { date: 'Apr 24', snippet: 'okay tomorrow. tomorrow first thing. for real.' },
      { date: 'May 02', snippet: 'still haven\u2019t sent it. it\u2019s getting weird now.' },
      { date: 'May 06', snippet: 'rewrote the draft. didn\u2019t send.' },
    ],
    vocab: ['tomorrow', 'definitely', 'after lunch', 'first thing', 'weird now'],
  },
  {
    id: 'tired-meanings',
    title: '"Tired" — three meanings',
    category: 'Vocabulary',
    observation: 'You used "tired" 23 times. Three distinct meanings. None defined.',
    count: '23 mentions',
    lastSeen: 'May 8',
    status: 'active',
    traceHits: [0, 2, 4, 7, 9, 11, 14, 16, 19, 22, 24, 27, 29],
    sources: [
      { date: 'Apr 11', snippet: '"tired" — context: post-meeting · pattern: crashed' },
      { date: 'Apr 19', snippet: '"tired" — context: 1am · pattern: wired' },
      { date: 'Apr 30', snippet: '"tired" — context: morning · pattern: dread' },
    ],
    vocab: ['tired (crashed)', 'tired (wired)', 'tired (dread)'],
  },
  {
    id: 'fine-crashed',
    title: '"Fine" precedes "crashed"',
    category: 'Co-occurrence',
    observation: 'The word "fine" appears in 7 entries that also include "crashed".',
    count: '7 co-occurrences',
    lastSeen: 'May 5',
    status: 'snoozed',
    traceHits: [4, 12, 19, 25],
    sources: [
      { date: 'Apr 12', snippet: 'said it was fine. crashed two hours later.' },
      { date: 'Apr 26', snippet: 'fine. then crashed.' },
    ],
    vocab: ['fine', 'crashed'],
  },
  {
    id: 'her',
    title: 'Conversation with her',
    category: 'Said-not-done',
    observation: 'Said you\u2019d talk to her on Apr 12. No mention of her since Apr 24.',
    count: '0 mentions in 14 days',
    lastSeen: 'Apr 24',
    status: 'resolved',
    traceHits: [2, 8],
    sources: [
      { date: 'Apr 12', snippet: 'okay i\u2019m going to actually talk to her this week.' },
      { date: 'Apr 24', snippet: 'haven\u2019t talked to her. not bringing it up again.' },
    ],
    vocab: ['actually', 'this week', 'her'],
  },
];

const ROAST_META = {
  date: 'May 8',
  sourceLine: 'Drawn from 31 entries · Last 30 days',
};

window.VESTIGE_DATA = { PERSONAS, PATTERNS, ROAST_META };
