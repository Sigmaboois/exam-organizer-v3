import parser
import reader
import json

def rename(metadata):
    with open("data/subjects.json","r",encoding="utf-8") as file:
        subjects = json.load(file)

if __name__ == "__main__":
    print("This is a test\n")
    pdfpath = input("Please input ur test file path:")
    file_extracted = reader.read_file(pdfpath)
    metadata_parsed=parser.meta_extract(file_extracted)
    rename(metadata_parsed)