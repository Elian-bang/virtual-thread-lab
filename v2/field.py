# -*- coding: utf-8 -*-
"""loadgen.out 의 'RESULT {json}' 에서 필드 하나를 꺼낸다.  사용: python field.py <loadgen.out> <field>"""
import json
import sys

line = open(sys.argv[1], encoding='utf-8').read().strip()
data = json.loads(line[line.index('{'):])
print(data[sys.argv[2]])
