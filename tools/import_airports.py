#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Import a complete, fixed OurAirports CC0 snapshot for nearby-airport search."""
import argparse
import csv
import hashlib
import io
from pathlib import Path

SOURCE_HASH = '190fa7298bfbbfe60ff49336ce6a2422f8c57754b615dab3ded67e9cf94081c0'
OUTPUT_HASH = 'a29575efd8c744bccc834b15000d87cb015cfccc5d7c11784f3a547db091696f'
HEADER = ['id', 'code', 'name', 'city', 'country', 'latitude', 'longitude', 'type']


def convert(raw):
    digest = hashlib.sha256(raw).hexdigest()
    if digest == OUTPUT_HASH:
        result = raw
    elif digest == SOURCE_HASH:
        rows = list(csv.DictReader(io.StringIO(raw.decode('utf-8-sig'))))
        if len(rows) != 83798:
            raise ValueError('Expected the complete 83,798-row snapshot')
        stream = io.StringIO(newline='')
        writer = csv.writer(stream, lineterminator='\n')
        writer.writerow(HEADER)
        for row in rows:
            code = next((row.get(key, '').strip() for key in ('iata_code', 'gps_code', 'ident') if row.get(key, '').strip()), '')
            if not code or not row['id'] or not row['latitude_deg'] or not row['longitude_deg']:
                raise ValueError('Airport row has missing required fields')
            writer.writerow([row['id'], code, row['name'], row['municipality'], row['iso_country'], row['latitude_deg'], row['longitude_deg'], row['type']])
        result = stream.getvalue().encode('utf-8')
    else:
        raise ValueError('Input snapshot SHA-256 is not recognized: ' + digest)
    if hashlib.sha256(result).hexdigest() != OUTPUT_HASH:
        raise ValueError('Normalized catalogue SHA-256 differs')
    rows = list(csv.reader(io.StringIO(result.decode('utf-8'))))
    if len(rows) != 83799 or rows[0] != HEADER:
        raise ValueError('Normalized catalogue is incomplete')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', type=Path, required=True)
    args = parser.parse_args()
    data = convert(args.input.read_bytes())
    target = Path(__file__).resolve().parents[1] / 'AirStudy/server/src/main/resources/airports/location-catalog.csv'
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)
    print('Imported 83,798 airport records; SHA-256 ' + OUTPUT_HASH)


if __name__ == '__main__':
    main()
