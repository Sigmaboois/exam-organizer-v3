import src.organize as organize

def multi_organize():
    pdf_paths = []
    while True:
        pdf_path = input("Please input the path of the file you would like to organize:\n")
        if pdf_path == "":
            break
        pdf_paths.append(pdf_path)
    
    root_path = input("Input the path where the exams should be sorted to:\n")
    # One bad file shouldn't stop the rest from being sorted
    failed = []
    for pdf_path in pdf_paths:
        try:
            organize.organize_exam(pdf_path,root_path)
        except Exception as error:
            failed.append(pdf_path)
            print(f"Skipped {pdf_path}: {error}")

    print(f"\nSorted {len(pdf_paths) - len(failed)} of {len(pdf_paths)} exams")
    for pdf_path in failed:
        print(f"Not sorted: {pdf_path}")
if __name__ == "__main__":
    print("This is a test\n")
    multi_organize()