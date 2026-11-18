import sys
tid, note = sys.argv[1], sys.argv[2]
t = open('TASKS.md', encoding='utf-8').read()
i = t.index('### ' + tid)
j = t.index('- [ ]', i)
e = t.index('\n', j)
t = t[:j] + '- [x]' + t[j+5:e] + '\n  - Notes: ' + note + t[e:]
open('TASKS.md', 'w', encoding='utf-8').write(t)
