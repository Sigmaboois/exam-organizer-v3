import parser
import reader
import json

def rename(metadata):
    print("Temporary")

if __name__ == "__main__":
    print("This is a test\n")
    pdfpath = input("Please input ur test file path:")
    file_extracted = reader.read_file(pdfpath)
    metadata_parsed=parser.meta_extract(file_extracted)
    rename(metadata_parsed)